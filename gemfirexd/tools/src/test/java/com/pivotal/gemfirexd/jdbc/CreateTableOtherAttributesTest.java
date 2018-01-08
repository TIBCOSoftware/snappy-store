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

import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Set;

import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.junit.JDBC;

public class CreateTableOtherAttributesTest extends JdbcTestBase {
  char fileSeparator = System.getProperty("file.separator").charAt(0);
  public static void main(String[] args) {
    TestRunner.run(new TestSuite(CreateTableOtherAttributesTest.class));
  }
  
  public CreateTableOtherAttributesTest(String name) {
    super(name); 
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    DistributionDescriptor.TEST_BYPASS_DATASTORE_CHECK = true;
  }

  @Override
  public void tearDown() throws Exception {
    super.tearDown();
    DistributionDescriptor.TEST_BYPASS_DATASTORE_CHECK = false;
  }

  public void testPersistentDirAttributesForDataStore() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");
    
    File file1 = new File("." + fileSeparator + "a" + fileSeparator + "b"
        + fileSeparator + "c" + fileSeparator);
    File file2 = new File("." + fileSeparator + "a" + fileSeparator + "b"
        + fileSeparator + "d" + fileSeparator);
    file1.mkdirs();    
    file2.mkdirs();
    File[] expectedDirs = new File[] { file1, file2 };
    s.execute("create diskstore teststore ('" + file1.getPath().substring(2) +"','"+file2.getPath().substring(2)+"')" );

    String persistentSuffix = "PERSISTENT 'teststore' "; 
    // Table is PR and range partition
    s .execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))   "
            + "partition by range (cid) (VALUES BETWEEN 0.0 AND 99.0)  "
            + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        deleteDir(file);
      }
      
    }
    //Table is PR with default partition
    for(File file : expectedDirs) {
     // assertTrue(file.createNewFile());
      file.mkdirs();
      assertTrue(file.exists());
    }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))   "            
            + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        deleteDir(file);
      }
    }
  //Table is PR with partition by PK
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid)) partition by primary key   "            
            + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        deleteDir(file);
      }
    }
  //Table is PR with partition by column
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" +
            " partition by column (cust_name)  " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        deleteDir(file);
      }
    }
    
  //Table is PR with partition by List
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY LIST ( tid ) ( VALUES (10, 20 )," 
            + " VALUES (50, 60), VALUES (12, 34, 45)) " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_PARTITION);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        deleteDir(file);
      }
    }
    
  //Table is PR  Partition by column colocated with another table
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid int , cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY column ( cid )  " + persistentSuffix);
    s.execute("create table trade.orders (oid decimal(30, 20), amount int, "
        + " tid int, cid int, primary key (oid))" 
        + "PARTITION BY column ( cid )  colocate with ( trade.customers) " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_PARTITION);
      rgn = Misc.getRegionForTable("TRADE.ORDERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_PARTITION);
    }
    finally {
      s.execute("drop table trade.orders");
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        deleteDir(file);
      }
    }
    
  //Table is PR  Partition by column colocated with another table
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid int , cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY column ( cid )  " + persistentSuffix);
    s.execute("create table trade.orders (oid decimal(30, 20), amount int, "
        + " tid int, cid int, primary key (oid))" 
        + "PARTITION BY column ( cid )  colocate with ( trade.customers) ");
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_PARTITION);
      rgn = Misc.getRegionForTable("TRADE.ORDERS", true);
      verify(rgn, null, DataPolicy.PARTITION);
    }
    finally {
      s.execute("drop table trade.orders");
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        deleteDir(file);
      }
    }
  //Table is Replicated
    for(File file : expectedDirs) {
      // assertTrue(file.createNewFile());
       file.mkdirs();
       assertTrue(file.exists());
     }
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "replicate " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      verify(rgn, expectedDirs, DataPolicy.PERSISTENT_REPLICATE);
    }
    finally {
      s.execute("drop table trade.customers");
      for(File file:expectedDirs) {
        deleteDir(file);
      }
    }
  }

  public void testIllegalTableAttributes() throws Exception
  {
    // This tests scenarios for illegal combinations of
    // partitioned and replicated clauses in CREATE TABLE
    // REPLICATE should not be allowed with any PARTITION BY
    //   clause or any of the partitioning subclauses
    // Included in this test is the testing of #45809
    // Each of these should throw the sqlstate X0Y90
    try {
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      // Test with BUCKETS clause
      s.execute("CREATE TABLE BAD1(COL1 INTEGER) BUCKETS 11 REPLICATE");
      fail("CREATE TABLE should have failed as illegal");
    }
    catch (SQLException e) {
      assertEquals(e.getSQLState(),"X0Y90");
    }
    try {
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      // Test with MAXPARTSIZE clause
      s.execute("CREATE TABLE BAD1(COL1 INTEGER) MAXPARTSIZE 100 REPLICATE");
      fail("CREATE TABLE should have failed as illegal");
    }
    catch (SQLException e) {
      assertEquals(e.getSQLState(),"X0Y90");
    }
    try {
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      // Test with RECOVERYDELAY clause
      s.execute("CREATE TABLE BAD1(COL1 INTEGER) RECOVERYDELAY 11 REPLICATE");
      fail("CREATE TABLE should have failed as illegal");
    }
    catch (SQLException e) {
      assertEquals(e.getSQLState(),"X0Y90");
    }
    try {
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      // Test with REDUNDANCY clause
      s.execute("CREATE TABLE BAD1(COL1 INTEGER) REDUNDANCY 2 REPLICATE");
      fail("CREATE TABLE should have failed as illegal");
    }
    catch (SQLException e) {
      assertEquals(e.getSQLState(),"X0Y90");
    }
    try {
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      // Test with PARTITION BY clause
      s.execute("CREATE TABLE BAD1(COL1 INTEGER) PARTITION BY COLUMN(COL1) REPLICATE");
      fail("CREATE TABLE should have failed as illegal");
    }
    catch (SQLException e) {
      assertEquals(e.getSQLState(),"X0Y90");
    }
    try {
      Connection conn = getConnection();
      Statement s = conn.createStatement();
      // Try with REPLICATE keyword first
      s.execute("CREATE TABLE BAD1(COL1 INTEGER) REPLICATE BUCKETS 100");
      fail("CREATE TABLE should have failed as illegal");
    }
    catch (SQLException e) {
      assertEquals(e.getSQLState(),"X0Y90");
    }
  }

  public void testCreateTableDDLUnitTest() throws Exception
  {
	  // This is the unit test for the CREATE TABLE DDL,
	  // including all GemFireXD extensions
	  
	  // Catch exceptions from illegal syntax
	  // Tests still not fixed marked FIXME
	  
	  // More tests to come :
	  // Testing of COLOCATE WITH
	  // Testing of ASYNCEVENTLISTENER/etc clauses
	  // Testing of partitioning values via SELECT and Gemfire region inspection
	    
	  // Array of DDL text to execute and sqlstates to expect
	  String[][] CreateTableUT = {
			  // CREATE two tables, second colocated with first, then drop first
	      { "CREATE TABLE TA1 (COL1 INTEGER NOT NULL PRIMARY KEY) PARTITION BY PRIMARY KEY", null },
	      { "CREATE TABLE TA2 (COL1 INTEGER NOT NULL) PARTITION BY COLUMN(COL1) COLOCATE WITH (TA1)", null },
	      // Should fail, can't drop - colocation exists
	      { "DROP TABLE TA1", "X0Y98" },
	      // Can drop TA2 first though
	      { "DROP TABLE TA2", null },
	      { "DROP TABLE TA1", null },
	      // Test CREATE TABLE with partitioned option (buckets/maxpartsize) and REPLICATE keyword
	      { "CREATE TABLE TB1 (X1 VARCHAR(15)) BUCKETS 10 REPLICATE", "X0Y90" },
	      { "CREATE TABLE TB2 (X1 VARCHAR(15)) REPLICATE BUCKETS 10", "X0Y90" },
	      { "CREATE TABLE TB3 (X1 VARCHAR(15)) PARTITION BY COLUMN(X1) BUCKETS 10 REPLICATE", "X0Y90" },
	      { "CREATE TABLE TB4 (X1 VARCHAR(15)) BUCKETS 10 PARTITION BY COLUMN(X1) REPLICATE", "X0Y90" },
	      { "CREATE TABLE TB5 (X1 VARCHAR(15)) REPLICATE PARTITION BY COLUMN(X1) MAXPARTSIZE 100", "X0Y90" },
	      { "CREATE TABLE TC1 (COL1 DECIMAL(5,2) NOT NULL) PARTITION BY COLUMN(COL1) BUCKETS 73 MAXPARTSIZE 200 RECOVERYDELAY 100", null },
	      // Multiple partitioning options not allowed
	      { "CREATE TABLE TD1 (C1 INTEGER, C2 SMALLINT, C3 BIGINT NOT NULL PRIMARY KEY) PARTITION BY PRIMARY KEY PARTITION BY COLUMN(COL1)", "42Y49" },
	      { "CREATE TABLE TD2 (C1 INTEGER, C2 SMALLINT, C3 BIGINT NOT NULL PRIMARY KEY) PARTITION BY COLUMN(COL1) PARTITION BY COLUMN(COL2)", "42Y49" },
	      { "CREATE TABLE TD3 (C1 INTEGER, C2 SMALLINT, C3 BIGINT NOT NULL PRIMARY KEY) PARTITION BY COLUMN(COL2) PARTITION BY LIST (COL2) (VALUES (1), VALUES(100))", "42Y49" },
	      { "CREATE TABLE TD4 (C1 INTEGER, C2 SMALLINT, C3 BIGINT NOT NULL PRIMARY KEY) PARTITION BY LIST (COL2) (VALUES (1), VALUES(100)) PARTITION BY (ABS(C3))", "42Y49" },
	      // PK but no PK in table
	      { "CREATE TABLE TE1 (C1 INTEGER NOT NULL) PARTITION BY PRIMARY KEY", "X0Y97" },
	      { "CREATE TABLE TE2 (\"PRIMARY KEY\" INTEGER NOT NULL) PARTITION BY PRIMARY KEY", "X0Y97" },
	      { "CREATE TABLE TE3 (\"PRIMARY KEY\" INTEGER NOT NULL) PARTITION BY COLUMN(\"PRIMARY KEY\")", null },
	      { "CREATE TABLE TE4 (C1 INTEGER NOT NULL, unique(C1)) PARTITION BY PRIMARY KEY", "X0Y97" },
	      { "CREATE TABLE TE5 (M1 CHAR(64) NOT NULL CONSTRAINT PK1 PRIMARY KEY) PARTITION BY PRIMARY KEY", null },
	      //FIXME Currently crashes, should throw error instead { "ALTER TABLE TE5 DROP PRIMARY KEY", null },
	      { "CREATE TABLE TE6 (C1 INTEGER NOT NULL PRIMARY KEY) PARTITION BY PRIMARY KEY(C1)", "42X01" },   // Can't give PK and column name in same stmt
	      // Partition by column
	      { "CREATE TABLE TF1 (C1 INTEGER) PARTITION BY COLUMN(C2)", "42X01" },
	      { "CREATE TABLE TF2 (C1 INTEGER, C2 INTEGER) PARTITION BY COLUMN(C1)", null },
	      //FIXME Currently crashes, should throw error instead { "ALTER TABLE TF2 DROP COLUMN C1", null },
	      { "CREATE TABLE TF3 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(C3, C2, C3, C1)", "42X12" },
	      { "CREATE TABLE TF4 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(C3, C3)", "42X12" },
	      { "CREATE TABLE TF5 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(null)", "42X01" },  // null not legal in column list
	      { "CREATE TABLE TF6 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(C3, 7)", "42X01" },
	      { "CREATE TABLE TF7 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(C3,'C2')", "42X01" }, // char lit isn't column name
	      { "CREATE TABLE TF8 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN('')", "42X01" },
	      { "CREATE TABLE TF9 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(C3+1)", "42X01" },  // expr isn't column name
	      { "CREATE TABLE TF10 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(values('H'))", "42X01" },
	      { "CREATE TABLE TF11 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(max(C3))", "42X01" },
	      { "CREATE TABLE TF12 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(select c2 from TF12)", "42X01" },  // syntax error for subselect
	      { "CREATE TABLE TF13 (C1 INTEGER, C2 VARCHAR(200), C3 REAL NOT NULL) PARTITION BY COLUMN(groupsintersect())", "42X01" },
	      // Test case sensitivity support for partitioning columns
	      { "CREATE TABLE TF14 (\"lowercase\" varchar(5) not null) PARTITION BY COLUMN (\"lowercase\")", null },
	      { "CREATE TABLE TF15 (\"lowercase\" varchar(5) not null, lowercase varchar(5)) PARTITION BY COLUMN (\"lowercase\", \"LOWERCASE\")", null },
	      // Test other datatypes
	      { "CREATE TABLE TF16 (c5 char(10) for bit data) PARTITION BY COLUMN (C5)", null },
	      { "CREATE TABLE TF17 (b1 BLOB(100K)) PARTITION BY COLUMN (B1)", null },
	      //FIXME allowed but probably meaningless - uncomment if supported { "CREATE TABLE TF18 (x1 XML) PARTITION BY COLUMN (X1)", null },
	      { "CREATE TABLE TF19 (id1 INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY) PARTITION BY COLUMN(ID1)", null },
	      { "CREATE TABLE TF20 (C1 int, c2 int, c3 int, c4 real, c5 double, c6 bigint, c7 double, c8 varchar(100), c9 char(50)) partition by column (c8, c3, c7, c1, c2, c9, c4, c6, c5)", null },
	      { "CREATE TABLE TF21 (C1 INT) PARTITION BY COLUMN(TF20.C2)", "42X01" },   // Does not work, but TF21.C1 does - unknown if this is a real bug
	      { "CREATE TABLE TF22 (C1 INTEGER NOT NULL PRIMARY KEY, COL2 DOUBLE) PARTITION BY COLUMN(COL2,C1)", null },
	      // Partition by list
	      { "CREATE TABLE TG1 (C1 INTEGER NOT NULL) PARTITION BY LIST (C1)", "42X01" },  // empty list
	      { "CREATE TABLE TG2 (C1 INTEGER) PARTITION BY LIST (C1) (VALUES(1), VALUES (2), VALUES ())", "42X01" },  // empty element in list
	      { "CREATE TABLE TG3 (C1 INTEGER) PARTITION BY LIST (C1) ()", "42X01" },
	      { "CREATE TABLE TG4 (C1 INTEGER) PARTITION BY LIST (C1) (VALUES(null))", "42X01" },  // null not valid here
	      { "CREATE TABLE TG5 (C1 SMALLINT) PARTITION BY LIST (C1) (VALUES (60000, 1000000000000))", "22003" },  // too big for column
	      { "CREATE TABLE TG6 (C1 INTEGER) PARTITION BY LIST(C1) (values (-21474835, 12345678901234))", "22003" },
	      { "CREATE TABLE TG7 (C1 double) PARTITION BY LIST(C1) (values (-15.0e+715))", "22003" },
	      { "CREATE TABLE TG8 (C1 double) partition by list(C1) (values (99.0e+999))", "22003" },
	      { "CREATE TABLE TG9 (C1 BIGINT) PARTITION BY LIST(C1) (values (9999999999999999999999999999999999999999999))", "22003" },
	      { "CREATE TABLE TG10 (C1 CHAR(10)) partition by list(c1) (values ('Thisisaverylongstring'))", "22003" },
	      { "CREATE TABLE TG11 (C1 CHAR(10)) partition by list(c1) (values (''))", null },   // blank padded
	      { "CREATE TABLE TG12 (C1 VARCHAR(10)) partition by list(c1) (values ('thisisanotherverylongstring'))", "22003" },
	      //FIXME succeeds, should fail { "CREATE TABLE TG13 (C1 xml) partition by list (c1) (values ('xml is not legal datatype'))", null },
	      { "CREATE TABLE TG14 (C1 INTEGER) PARTITION BY LIST (C1) (values ('15'))", "22003" },
	      { "CREATE TABLE TG15 (C1 CHAR(30)) PARTITION BY LIST (C1) (values (15))", "22003" },
	      //FIXME succeeds, should fail datatype mismatch { "CREATE TABLE TG16 (C1 INTEGER) PARTITION BY LIST (C1) (values (13.555))", null },
	      { "CREATE TABLE TG17 (C1 INTEGER) partition by list (c1) (values (max(c1)))", "22003" },  // probably should be 42903
	      { "CREATE TABLE TG18 (C1 INTEGER) partition by list (c1) (values (c1=5))", "42X01" },  // syntax error, equality not permitted in values
	      { "CREATE TABLE TG19 (C1 INTEGER) partition by list (c1) (values (values(5)))", "42X01" },  // values not recursive
	      { "CREATE TABLE TG20 (C1 INTEGER) partition by list (c1) (values (c1+5))", "22003" },  // wrong expression in list
	      //FIXME fails, should succeed as 1+5 is constant 6 { "CREATE TABLE TG201 (C1 INTEGER) partition by list (c1) (values (1+5))", "22003" },  
	      { "CREATE TABLE TG21 (C1 INTEGER) partition by list (c1) (values (select 5 from sysibm.sysdummy1))", "42X01" },
	      { "CREATE TABLE TG22 (C1 INTEGER) partition by list (c1) (values (+infinity))", "42X04" },  // 'infinity' only for range partitioning
	      { "CREATE TABLE TG23 (C1 INTEGER) partition by list (c1) (values (CAST(5 as integer)))", null },
	      { "CREATE TABLE TG24 (C1 SMALLINT NOT NULL WITH DEFAULT 10) PARTITION BY LIST(C1) (VALUES (DEFAULT))", "42X01" },
	      //FIXME succeeds, should fail as dup values in list { "CREATE TABLE TG25 (C1 INTEGER) PARTITION BY LIST (C1) (VALUES (1,10,1))", null },
	      { "CREATE TABLE TG25 (C1 INTEGER) PARTITION BY LIST (C1) (VALUES (1,10), VALUES(1))", "42Y49" },
	      { "CREATE TABLE TG26 (C1 CHAR(10)) PARTITION BY LIST (C1) (VALUES ('ABC'), VALUES ('ABC   '))", "42Y49" },
	      { "CREATE TABLE TG27 (C1 INTEGER, C2 INTEGER) PARTITION BY LIST(C1,C2) (VALUES (5,5))", "42X01" },   // no multi-column lists allowed
	      // Partition by range
	      { "CREATE TABLE TH1 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN 4 and 2)", "0A000" },
	      //FIXME gives odd error about casting, should throw overlapping range err { "CREATE TABLE TH2 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN 2 and 4, VALUES BETWEEN 2 and 4)", null },
	      //FIXME same should throw overlapping range err { "CREATE TABLE TH2 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN 2 and 4, VALUES BETWEEN 3 and 5)", null },
	      { "CREATE TABLE TH4 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN 4 and 4)", "0A000" },
	      { "CREATE TABLE TH5 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN null and null)", "42X01" },  // null not valid in this values clause
	      { "CREATE TABLE TH6 (C1 VARCHAR(10)) PARTITION BY RANGE(C1) (VALUES BETWEEN 'A' and '')", "0A000" },
	      { "CREATE TABLE TH7 (C1 INTEGER NOT NULL WITH DEFAULT 10) PARTITION BY RANGE(C1) (VALUES BETWEEN 4 and DEFAULT)", "42X01" },
	      { "CREATE TABLE TH8 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN 4+C1 and 10)", "22003" },
	      { "CREATE TABLE TH9 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN -infinity and +infinity)", "42X77" },  // Range must have a bound
	      { "CREATE TABLE TH10 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN MAX(C1) and 7)", "22003" },
	      { "CREATE TABLE TH11 (C1 INTEGER) PARTITION BY RANGE(C1) (VALUES BETWEEN 4 and 6, VALUES BETWEEN CAST(7 AS INTEGER) AND CAST(11 as INTEGER))", null },
	      { "CREATE TABLE TH12 (C1 CHAR(5)) PARTITION BY RANGE(C1) (VALUES BETWEEN '' and ' ')", "0A000" },  // blankpads are equivalent
	      //FIXME succeeds, should fail with datatype mismatch { "CREATE TABLE TH13 (C1 INT) PARTITION BY RANGE(C1) (VALUES BETWEEN 5.777 and 6)", null },
	      { "CREATE TABLE TH14 (C1 SMALLINT) PARTITION BY RANGE(C1) (VALUES BETWEEN 18000 and 34000, VALUES BETWEEN 40000 and 100000)", "22003" },
	      { "CREATE TABLE TH15 (C1 VARCHAR(10)) PARTITION BY RANGE(C1) (VALUES BETWEEN -100 and 'BigValue')", "42818" },  // datatype mismatch
	      // Partition by expression
	      { "CREATE TABLE TI1 (C1 VARCHAR(10) NOT NULL) PARTITION BY (YEAR(C1))", null },
	      { "CREATE TABLE TI2 (C1 DOUBLE) PARTITION BY (SUBSTR(C1,1,2))", "42X25" },  // datatype mismatch in func

	      { "CREATE TABLE TI3 (C1 CLOB(5000)) PARTITION BY (ABS(C1))", "42X25" },

	      { "CREATE TABLE TI4 (C1 BIGINT) PARTITION BY (DSID())", null },
              { "CREATE TABLE TI5 (C1 INT) PARTITION BY (MAX(C1))", "42903" },

	      { "CREATE TABLE TI6 (C1 INT, C2 INT) PARTITION BY (C1+C2)", null },
	      { "CREATE TABLE TI7 (C1 BIGINT, C2 SMALLINT) PARTITION BY (C1!=C2)", "42X01" },   // syntax error for expression
	      { "CREATE TABLE TI8 (C1 DATE, C2 TIME) PARTITION BY (TIMESTAMP(C1,C2))", null },  // pointless, but neat
	      { "CREATE TABLE TI9 (C1 INT) PARTITION BY (EXISTS (SELECT * FROM SYS.MEMBERS))", "42X01" },  // no subquery allowed
	      { "CREATE TABLE TI10 (C1 INT, C2 BIGINT) PARTITION BY (C2+1)", null },
	      { "CREATE TABLE TI12 (C1 INT) PARTITION BY (VALUES(5))", "42X01" },
	      { "CREATE TABLE TI13 (C1 DOUBLE) PARTITION BY (SELECT C1 FROM TI13)", "42X01" },
	      { "CREATE TABLE TI14 (C1 DOUBLE) PARTITION BY (SUM(C1)/COUNT(C1))", "42903" },
	      //FIXME may be valid { "CREATE TABLE TI15 (C1 VARCHAR(128)) PARTITION BY (CURRENT_USER)", "42X01" },  // not sure, this may be valid, throws node mismatch
	      //FIXME may be valid { "CREATE TABLE TI16 (C1 VARCHAR(128)) PARTITION BY (LENGTH(CURRENT_USER))", "42X01" },
	      { "CREATE TABLE TI17 (C1 DOUBLE) PARTITION BY (RANDOM())", null },    // good luck with that!
	      { "CREATE TABLE TI18 (C1 DOUBLE) PARTITION BY (PI())", null },
	      //FIXME succeeds, should throw type mismatch error { "CREATE TABLE TI19 (C1 INT) PARTITION BY (LENGTH(C1))", null },
	      //FIXME crashes, should work? { "CREATE TABLE TI20 (C1 VARCHAR(15)) PARTITION BY (YEAR(C1))", null },
	      { "CREATE TABLE TI21 (C1 INT) PARTITION BY (TAN(C1))", null },
	      { "CREATE TABLE TI22 (C1 INT, C2 VARCHAR(100)) PARTITION BY (C1/C2)", null }, 
	      { "CREATE TABLE TI23 (C1 INT) PARTITION BY (C1/0)", null },      // dangerous, but works
	      //FIXME throws div-by-zero as expected but also drops connection, should just return div-0 err { "INSERT INTO TI23 VALUES (5)", null },
	      { "CREATE TABLE TI24 (C1 SMALLINT) PARTITION BY (C1>5)", "42X01" },
	      { "CREATE TABLE TI25 (C1 VARCHAR(100)) PARTITION BY (GROUPSINTERSECT(C1,'MYGROUPS'))", null },
	      { "CREATE TABLE TI26 (C1 INT) PARTITION BY (mod(C1,5))", null },
	      //FIXME throws proc-not-found, proc exists { "CREATE TABLE TI27 (C1 CHAR(20)) PARTITION BY (SYS.REBALANCE_ALL_BUCKETS())", null },
	      //FIXME throws ConditionalNode cast error, but should be legal { "CREATE TABLE TI28 (COL1 INTEGER NOT NULL) PARTITION BY (CASE WHEN COL1=5 THEN 1 ELSE 2 END)", "42X01" },
	      { "CREATE TABLE TI29 (COL1 CHAR(10)) PARTITION BY (COL1)", null },  // expression just as column name is legal
	      // All datatypes at once
	      { "CREATE TABLE TJ1 (C1 SMALLINT, C2 INTEGER, C3 BIGINT, C4 DOUBLE, C5 DECIMAL(10,2), C6 CHAR(100), C7 VARCHAR(200), C8 CHAR(100) FOR BIT DATA, C9 VARCHAR(100) FOR BIT DATA, C10 TIME, C11 DATE, C12 TIMESTAMP, C13 CLOB(100), C14 BLOB(100)) PARTITION BY COLUMN (C14, c13, c12, c11, c10, c9, c8, c7, c6, c5, c4, c3, c2, c1)", null },
	      //FIXME crashes, should throw typecast error { "CREATE TABLE TJ2 (C1 CHAR(1000)) PARTITION BY COLUMN(C1)", null },
	      // CREATE TABLE AS ... <query> syntax (Derby logic testing)
	      { "CREATE TABLE TK1 AS SELECT * FROM TJ1 WITH NO DATA PARTITION BY COLUMN(C1, C2, C3, C4, C5, C6, C7, C8, C9, C10, C11, C12, C13, C14)", null },
	      { "CREATE TABLE TK2(C1) AS VALUES(5) WITH NO DATA", null },
	      { "CREATE TABLE TK3(C1) AS VALUES('Hello') WITH NO DATA", null },
	      { "CREATE TABLE TK4(C1) AS VALUES(9.9) WITH NO DATA", null },
	      { "CREATE TABLE TK41(C1 INTEGER) AS VALUES(10.55) WITH NO DATA", "42X01" },  // can't give column name and datatype too
	      { "CREATE TABLE TK5 AS SELECT PORT FROM SYS.MEMBERS WITH NO DATA", null },
	      { "CREATE SYNONYM S1 FOR SYS.JARS", null },
	      { "CREATE TABLE TK6 AS SELECT * FROM S1 WITH NO DATA", null },
	      { "DECLARE GLOBAL TEMPORARY TABLE G1 (COL1 DOUBLE) NOT LOGGED", null },
	      { "CREATE TABLE TK7 AS SELECT COL1 FROM SESSION.G1 WITH NO DATA", null },
	      { "CREATE TABLE TK8 AS SELECT TABLESCHEMANAME FROM SYS.SYSTABLES WITH NO DATA", null },
	      { "CREATE TABLE TK9 AS SELECT * FROM TK9 WITH NO DATA", "42X05" },   // jumping the gun!
	      { "CREATE TABLE TK10 AS VALUES(NULL) WITH NO DATA", "42X07" },
	      { "CREATE TABLE SOURCE1 (COL1 INTEGER NOT NULL PRIMARY KEY)", null },
	      { "CREATE TABLE TK11 AS SELECT * FROM SOURCE1 WITH NO DATA PARTITION BY PRIMARY KEY", "X0Y97" },  // we don't get the pkey from AS <query>  
	      { "CREATE TABLE TK12(X) AS SELECT DSID() FROM SOURCE1 WITH NO DATA", null },
	      { "CREATE TABLE TK13(X) AS SELECT '' FROM SYSIBM.SYSDUMMY1 with no data", "42X44" },
	      // Create table w/self-ref foreign key
	      { "create table employee2 (employee_number int not null, x int not null, employee_name varchar(20), manager_employee_number int, y int not null, primary key(employee_number,x), constraint man2 foreign key (manager_employee_number,y) references employee2 on delete restrict)", null },
	      { "insert into employee2 values (5, 6, 'Randy', 1, 2)", "23503" },      // violation of self-ref foreign key
	      { "insert into employee2 values (5, 6, 'Randy', 5, 6)", null },		  // self-ref foreign key satisfied
	      // Create table w/generated columns and illegal combinations
	      { "create table z3 (x int, y int generated always as identity (start with 2))", "0A000" },  // GEN ALWAYS not supported w/START WITH
	      { "create table z3 (x int, y int generated always as identity (increment by 5))", "0A000" }, // GEN ALWAYS not supported w/INCREMENT BY
	      { "create table z3 (x int, y int generated always as identity (start with 2, increment by 6))", "0A000" },
	      // multiple NOT NULL/NULL uses
	      { "create table nn1 (col1 int null null)", "42Y49" },
	      { "create table nn2 (col1 int not null not null)", "42Y49" },
	      // Eviction/expiration tests
	      { "create table e1 (col1 integer) REPLICATE eviction by lrumemsize 100", "38000" },  // IllegalStateException
	      { "create table e2 (col1 integer) eviction by lrumemsize 100 evictaction overflow", null },
	      { "create table e3 (col1 integer) eviction by lrumemsize 100 evictaction destroy", null }, 
	      { "create table e4 (col1 integer) partition by column(col1) eviction by lrumemsize 100", null },
	      { "create table e5 (col1 integer) eviction by lrumemsize 100 evictaction overflow eviction by lrumemsize 200 evictaction overflow", "42Y49" },
	      { "create table e6 (col1 integer) eviction by lrumemsize 100 evictaction overflow eviction by lruheappercent evictaction overflow", "42Y49" },
	      { "create table e7 (col1 integer) eviction by lrumemsize 100 evictaction overflow eviction by lrucount 5 evictaction overflow", "42Y49" },
	      { "create table e8 (col1 integer) partition by column(col1) eviction by lrumemsize -100", "42X44" },
	      { "create table e9 (col1 integer) partition by column(col1) eviction by lrumemsize 0", "42X44" },  // Cannot be zero memsize
	      { "create table e10 (col1 integer) partition by column(col1) eviction by lrucount -6", "42X44" },
	      { "create table e11 (col1 integer) REPLICATE expire table with timetolive 5000 action destroy", null },  // Not supported for partitioned regions
	      { "create table e12 (col1 integer) REPLICATE expire table with idletime 5000 action destroy", null },
	      { "create table e13 (col1 integer) REPLICATE expire entry with timetolive 5000 action destroy", null },
	      { "create table e14 (col1 integer) REPLICATE expire entry with idletime 5000 action destroy", null },
	      { "create table e15 (col1 integer) expire table with timetolive 500 action invalidate", "42X01" },   // INVALIDATE keyword removed from grammar
	      { "create table e16 (col1 integer) REPLICATE expire table with timetolive 500 action destroy expire table with timetolive 400 action destroy", "42Y49" },
	      { "create table e17 (col1 integer) REPLICATE expire entry with idletime 100 action destroy expire entry with idletime 500 action destroy", "42Y49" }
	  };
/* TODO : still to come : 
--CREATE TABLE with server groups that do not exist
--CREATE TABLE with server groups as illegal constant (NULL, zero-length string)
--CREATE TABLE with duplicate server group in list
--CREATE TABLE with gatewaysender that does not exist
--CREATE TABLE with gatewaysender as illegal constant (NULL, zero-length string)
--CREATE TABLE with duplicate gatewaysenders in list
--CREATE TABLE with gatewaysender, then DROP GATEWAYSENDER, is this allowed?
--CREATE TABLE with asynclistener that does not exist
--CREATE TABLE with asynclistener as illegal constant (NULL, zero-length string)
--CREATE TABLE with asynclistener with duplicates in list
--CREATE TABLE with asynclistener, then DROP ASYNCLISTENER, is this allowed?
--CREATE TABLE with eviction actions both OVERFLOW and DESTROY
--CREATE TABLE with eviction action OVERFLOW and disk-store-name given
--CREATE TABLE with illegal expiration second values (negative, is zero allowed?)
--CREATE TABLE with eviction DESTROY and REPLICATE table (throws IllegalException, should be SQLException found in grammar?)
--CREATE TABLE with MAXPARTSIZE that is negative, zero or illegal
--CREATE TABLE with RECOVERTDELAY that is negative (allowed), zero or illegal
--CREATE TABLE with BUCKETS that is negative, zero or illegal
--CREATE TABLE with BUCKETS on one table, CREATE second TABLE COLOCATED with first, verify
--  that the BUCKETS for the second table equals that of the first (inherited)
--CREATE TABLE with BUCKETS 3 but with 5 RANGES or 5 LISTS of values (buckets must be at least range/list count)
--CREATE TABLE with REDUNDANCY that is negative, zero (allowed) or illegal (max of 3?)
--CREATE TABLE with multiple COLOCATE WITH clauses
--CREATE TABLE with COLOCATE WITH clause but no PARTITION BY clause (even if default is partition, should fail)
--CREATE TABLE COLOCATE WITH the same table
--CREATE TABLE COLOCATE WITH a replicated table
--CREATE TABLE COLOCATE WITH a DGTT
--CREATE TABLE COLOCATE WITH a view
--CREATE TABLE COLOCATE with a VTI
--CREATE TABLE COLOCATE WITH a synonym to a partitioned table
--CREATE TABLE COLOCATE WITH a system table
--CREATE TABLE COLOCATE WITH a table that is partitioned on a different column/range/list/expression
-- CREATE TABLE COLOCATE WITH two tables with partition-by-expression is broken, it allows anything as long as other table is also partitioned by some expression!
--CREATE TABLE COLOCATE WITH where first table's column names are different, but location, datatype and nullability match
--CREATE TABLE COLOCATE WITH where first table's column has different location but identical name, datatype and nullability
--CREATE TABLE COLOCATE WITH where first table's column datatype is different, but name, location and nullability are identical
--CREATE TABLE COLOCATE WITH where first table's column nullability is different, but name, location and datatype are identical
--CREATE TABLE COLOCATE WITH a table where both give BUCKETS but BUCKETS value is not equal
--CREATE TABLE COLOCATE WITH a table where both give REDUNDANCY but redundancy value is not equal
--CREATE TABLE COLOCATE WITH multiple table names
--CREATE TABLE COLOCATE WITH and REPLICATE keywords
--CREATE TABLE COLOCATE WITH table that does not belong to same server groups as first table
--CREATE TABLE with PERSISTENT keyword in combination to PARTITION clauses or REPLICATE keyword
--CREATE TABLE with disk-store-name as illegal value (zero-length string)
--CREATE TABLE with disk-store-name but diskstore does not exist
--CREATE TABLE with disk-store-name PERSISTENT but then DROP DISKSTORE - this should be disallowed
--CREATE TABLE with multiple PERSISTENT-diskstore combinations (should only allow one)
--CREATE TABLE with ASYNCHRONOUS persistence and SYNCHRONOUS persistence (should only allow one)
--CREATE TABLE with ASYNCHRONOUS/SYNCHRONOUS keyword before PERSISTENT keyword
--CREATE TABLE with all possible clauses (except REPLICATE) at the same time, in any order
--Verify that SYSTABLES has all info correctly.	
--CREATE TABLE with CHECK CONSTRAINTS for column and table, see if GemFireXD functions can be used there
--CREATE TABLE PARTITION BY all column types, add in a few rows (some big, some small), verify that SELECT WHERE partitioning key = value returns correct value
 */
	    Connection conn = TestUtil.getConnection();
	    Statement stmt = conn.createStatement();
	    // Go through the array, execute each string[0], check sqlstate [1]
	    // This will fail on the first one that succeeds where it shouldn't
	    // or throws unknown exception
        JDBC.SQLUnitTestHelper(stmt,CreateTableUT);
	// TODO : verify columns in catalog
  	}

  private void verify(Region rgn, File [] expectedDirs,
      DataPolicy expectedDP) throws IOException {
    RegionAttributes ra = rgn.getAttributes();
    assertEquals(expectedDP,ra.getDataPolicy());
    if(expectedDirs == null) {
      assertNull(ra.getDiskStoreName());
      return ;
    }else {
    File actualDirs[] = Misc.getGemFireCache().findDiskStore("TESTSTORE").getDiskDirs();
    assertEquals(actualDirs.length, expectedDirs.length);
    Set<String> expected = new HashSet(expectedDirs.length);
    for(File file :expectedDirs) {
      expected.add(file.getCanonicalPath());
    }   
    for(File file: actualDirs) {
      assertTrue(expected.remove(file.getCanonicalPath()));
    }    
    assertTrue(expected.isEmpty());
    }
  }
  
}
