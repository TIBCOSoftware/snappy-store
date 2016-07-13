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
package com.pivotal.gemfirexd.ddl;

import java.io.File;
import java.io.PrintWriter;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import com.gemstone.gemfire.cache.CacheException;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;

import io.snappydata.test.dunit.SerializableRunnable;

public class ExportImportTestDUnit extends DistributedSQLTestBase {

  private static final long serialVersionUID = 1L;

  public ExportImportTestDUnit(String name) {
    super(name);
  }

  public void testSimpleExport_PR() throws Exception {
    // Start one client a four servers
    startVMs(1, 3);

    clientSQLExecute(1, "create table t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20) not null, "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    // Check for PARTITION BY RANGE
    clientSQLExecute(
        1,
        "insert into t1 values (1, 1, 'kingfisher'), (1, 2, 'jet'), (2, 1, 'ai'), (3, 1, 'ial')");

    clientSQLExecute(1,
        "CALL SYSCS_UTIL.EXPORT_TABLE(null, 't1', 'export_PR.flt', null, null, null)");

  }
  
  /**
   * Test for simple import of data (in all the columns of the table) through a data file.
   */
  public void testSimpleImport_PR() throws Exception {
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20), "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    //create import data file from which data is to be imported into the table
    PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
    p.println("1354,11,Airbus");
    p.println("7363,12,Boeing");
    p.close();
    
    //call import data procedure passing column names as parameter 
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_DATA('APP', 'T1', null, null, 'import_test_data.txt', null, null, null, 1)");
    
    //verify data has been imported successfully
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("select count(*) from app.t1");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("Number of rows in table should be 2", 2, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Test for multi-threaded import of data (in all the columns of the table) through a data file.
   * Added to verify defect #46758
   */
  public void testMultiThreadedImportTable_PR() throws Exception {
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20), "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    //create import data file from which data is to be imported into the table
    PrintWriter p = new PrintWriter(new File("import_table_ex.txt"));
    p.println("1354,11,Airbus");
    p.println("7363,12,Boeing");
    p.println("2562,13,Airbus");
    p.println("6355,14,Boeing");
    p.println("8376,15,Airbus");
    p.close();
    
    //call import table procedure with 6 threads (data file has 5 rows)
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_table_ex.txt', null, null, null, 0, 0, 6, 0, null, null)");
    
    //verify data has been imported successfully
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("select count(*) from app.t1");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("Number of rows in table should be 5", 5, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_table_ex.txt").delete();
    }
  }
  
  /**
   * Test for simple import of data through a data file.
   * The data file is not proper since it has values for only 2 columns 
   * while the table has 3 columns.
   */
  public void testSimpleImportWithImproperDataFile_PR() throws Exception {
    boolean exceptionOccurredDuringImport = false;
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20), "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    //create import data file from which data is to be imported into the table
    PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
    p.println("1354,11");
    p.println("7363,12");
    p.close();
    
    //call import data procedure passing column names as parameter 
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_DATA('APP', 'T1', null, null, 'import_test_data.txt', null, null, null, 1)");
    
    } catch (SQLException e) { 
      exceptionOccurredDuringImport = true;
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
    assertTrue("exception should occur during import", exceptionOccurredDuringImport);
  }
  
  /**
   * Added to reproduce defect #51233.
   * Import data on selective columns using a data file. 
   * Column names are passed as parameters to the procedure.
   */
  public void testImportWithSelectiveColumnNamesAsParameters_PR() throws Exception {
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20), "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    //create import data file from which data is to be imported into the table
    PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
    p.println("1354,11");
    p.println("7363,12");
    p.close();
    
    //call import data procedure passing column names as parameter 
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_DATA('APP', 'T1', 'FLIGHT_ID,SEGMENT_NUMBER', null, 'import_test_data.txt', null, null, null, 1)");
    
    //verify data has been imported successfully
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("select count(*) from app.t1");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("Number of rows in table should be 2", 2, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Import data on selective columns using a data file. 
   * Column names are passed as parameters to the procedure.
   */
  public void testImportWithAllColumnNamesAsParameters_PR() throws Exception {
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20), "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    //create import data file from which data is to be imported into the table
    PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
    p.println("1354,11,Airbus");
    p.println("7363,12,Boeing");
    p.close();
    
    //call import data procedure passing column names as parameter 
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_DATA('APP', 'T1', 'FLIGHT_ID,SEGMENT_NUMBER,AIRCRAFT', null, 'import_test_data.txt', null, null, null, 1)");
    
    //verify data has been imported successfully
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("select count(*) from app.t1");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("Number of rows in table should be 2", 2, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Import data on selective columns using a data file. 
   * Column names are given in first line in the data file.
   */
  public void testImportWithSelectiveColumnNamesInDataFile_PR() throws Exception {
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20), "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    //create import data file from which data is to be imported into the table
    PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
    p.println("FLIGHT_ID,SEGMENT_NUMBER");
    p.println("1354,11");
    p.println("7363,12");
    p.close();
    
    //call import data procedure. Column names are part of data file above. 
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_DATA('APP', 'T1', null, null, 'import_test_data.txt', null, null, null, 1)");
    
    //verify data has been imported successfully
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("select count(*) from app.t1");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("Number of rows in table should be 2", 2, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  } 
  
  /**
   * Import data on selective columns. The column names as passed as parameters
   * as well as are given in first line in the data file.
   */
  public void testImportWithSelectiveColumnNamesAsParamsAndInDataFile_PR() throws Exception {
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20), "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    //create import data file from which data is to be imported into the table
    PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
    p.println("FLIGHT_ID,SEGMENT_NUMBER");
    p.println("1354,11");
    p.println("7363,12");
    p.close();
    
    //call import data procedure. Column names are part of data file above. 
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_DATA('APP', 'T1', 'FLIGHT_ID,SEGMENT_NUMBER', null, 'import_test_data.txt', null, null, null, 1)");
    
    //verify data has been imported successfully
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("select count(*) from app.t1");
    ResultSet rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals("Number of rows in table should be 2", 2, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }


  /**
   * Import data on selective columns with special charactors. The column names are
   * given in first line in the data file.
   */
  public void testImportWithSelectiveSpecialColumnNamesAsParamsAndInDataFile_PR() throws Exception {
    try {
      // Start one client a three servers
      startVMs(1, 3);

      //create the table
      clientSQLExecute(1, "create table app.t1(\"~flight_id\" int not null, "
          + "segment_number int not null, aircraft varchar(20), "
          + "CONSTRAINT FLIGHTS_PK PRIMARY KEY ( SEGMENT_NUMBER))");

      //create import data file from which data is to be imported into the table
      PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
      p.println("\"~flight_id\",\"SEGMENT_NUMBER\"");
      p.println("1354,11");
      p.println("7363,12");
      p.close();

      //call import data procedure. Column names are part of data file above.
      clientSQLExecute(1,
          "CALL SYSCS_UTIL.IMPORT_DATA('APP', 'T1', null, null, 'import_test_data.txt', null, null, null, 1)");

      //verify data has been imported successfully
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
      st.execute("select count(*) from app.t1");
      ResultSet rs = st.getResultSet();
      assertTrue(rs.next());
      assertEquals("Number of rows in table should be 2", 2, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }

  /**
   * Import data with no column names specified, neither through parameters to the 
   * procedure nor in the data file.
   */
  public void testImportWithNoColumnNamesSpecified_PR() throws Exception {
    boolean exceptionOccurredDuringImport = false;
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20), "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER))");

    //create import data file from which data is to be imported into the table
    PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
    p.println("1354,11");
    p.println("7363,12");
    p.close();
    
    //call import data procedure. Column names are part of data file above. 
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_DATA('APP', 'T1', null, null, 'import_test_data.txt', null, null, null, 1)");
    } catch (SQLException e) {
      exceptionOccurredDuringImport = true;
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
    assertTrue("exception should occur during import", exceptionOccurredDuringImport);
  }

  public void testSimpleExport_REPLICATE() throws Exception {
    // Start one client a four servers
    startVMs(1, 3);

    clientSQLExecute(
        1,
        "create table t1(flight_id int not null, "
            + "segment_number int not null, aircraft varchar(20) not null, "
            + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID, SEGMENT_NUMBER)) replicate");

    // Check for PARTITION BY RANGE
    clientSQLExecute(
        1,
        "insert into t1 values (1, 1, 'kingfisher'), (1, 2, 'jet'), (2, 1, 'ai'), (3, 1, 'ial')");

    clientSQLExecute(
        1,
        "CALL SYSCS_UTIL.EXPORT_TABLE(null, 't1', 'export_REPLICATE.flt', null, null, null)");

  }

  public void testSimpleExport_joinQuery_PR() throws Exception {
    // Start one client a four servers
    startVMs(1, 3);

    clientSQLExecute(1, "create table t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20) not null, "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID))");

    clientSQLExecute(1, "create table t2(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20) not null, "
        + "CONSTRAINT FLIGHTS_FK foreign KEY (FLIGHT_ID) references t1(flight_id))");

    // Check for PARTITION BY RANGE
    clientSQLExecute(
        1,
        "insert into t1 values (1, 1, 'kingfisher'), (2, 2, 'jet'), (3, 1, 'ai'), (4, 1, 'ial')");

    clientSQLExecute(
        1,
        "insert into t2 values (1, 1, 'kingfisher_dep'), (2, 2, 'jet_dep'), (3, 1, 'ai_dep'), (4, 1, 'ial_dep')");

    clientSQLExecute(
        1,
        "CALL SYSCS_UTIL.EXPORT_QUERY("
            + "'select * from t1, t2 where t1.flight_id = t2.flight_id', 'export_joinQuery_pr.flt', null, null, null)");

  }

  public void testSimpleExport_joinQuery_REPLICATE() throws Exception {
    // Start one client a four servers
    startVMs(1, 3);

    clientSQLExecute(1, "create table t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20) not null, "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID)) replicate");

    clientSQLExecute(1, "create table t2(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20) not null, "
        + "CONSTRAINT FLIGHTS_FK foreign KEY (FLIGHT_ID) references t1(flight_id)) replicate");

    // Check for PARTITION BY RANGE
    clientSQLExecute(
        1,
        "insert into t1 values (1, 1, 'kingfisher'), (2, 2, 'jet'), (3, 1, 'ai'), (4, 1, 'ial')");

    clientSQLExecute(
        1,
        "insert into t2 values (1, 1, 'kingfisher_dep'), (2, 2, 'jet_dep'), (3, 1, 'ai_dep'), (4, 1, 'ial_dep')");

    clientSQLExecute(
        1,
        "CALL SYSCS_UTIL.EXPORT_QUERY("
            + "'select * from t1, t2 where t1.flight_id = t2.flight_id', 'export_joinQuery_replicate.flt', null, null, null)");
  }

  public void testSimpleExport_non_colocatedjoinQuery_PR() throws Exception {
    // Start one client a four servers
    startVMs(1, 3);

    clientSQLExecute(1, "create table t1(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20) not null, "
        + "CONSTRAINT FLIGHTS_PK PRIMARY KEY (FLIGHT_ID))");

    clientSQLExecute(1, "create table t2(flight_id int not null, "
        + "segment_number int not null, aircraft varchar(20) not null)");

    // Check for PARTITION BY RANGE
    clientSQLExecute(
        1,
        "insert into t1 values (1, 1, 'kingfisher'), (2, 2, 'jet'), (3, 1, 'ai'), (4, 1, 'ial')");

    clientSQLExecute(
        1,
        "insert into t2 values (1, 1, 'kingfisher_dep'), (2, 2, 'jet_dep'), (3, 1, 'ai_dep'), (4, 1, 'ial_dep')");

    try {
    clientSQLExecute(
        1,
        "CALL SYSCS_UTIL.EXPORT_QUERY("
            + "'select * from t1, t2 where t1.flight_id = t2.flight_id', 'export_noncolojoinQuery_pr.flt', null, null, null)");
    fail("should have  thrown exception");
    }
    catch (SQLException sqle) {
      return;
    }
    fail("should have got the exception");
  }
  
  /**
   * Test for defect #51375
   */
  public void testImportTable_PR_bug51375() throws Exception {
    try {
    // Start one client a three servers
    startVMs(1, 3);

    //create the table
    clientSQLExecute(1, "create table app.t1(flight_id int not null primary key, "
        + "aircraft varchar(20),segment_number int not null ) ");
    
    PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
    p.println("1354,\" \"\"Airbus\"\"\", 11");
    p.println("1355,\"\"\"Boeing\"\"\", 12");
    p.close();
    
    clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_test_data.txt', null, null, null, 1, 0, 1, 0, null, null)");
    
    //verify data has been imported successfully
    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("select aircraft, flight_id from app.t1");
    ResultSet rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
      int flight_id = rs.getInt(2);
      if (flight_id == 1354) {
        assertEquals("value of column aircraft is incorrect: ", " \"Airbus\"", rs.getString(1));
      } else if (flight_id == 1355) {
        assertEquals("value of column aircraft is incorrect: ", "\"Boeing\"", rs.getString(1));
      }
    }
    assertTrue("Number of rows in table is incorrect: ", (count == 2));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Test for defect #51274. 
   * Test import functionality with lockTable.
   * Verify that the operations after the import table, complete without any issues.
   * i.e. the lock held on table is released after the 'import table' operation completes.
   */
  public void testImportTable_PR_bug51274() throws Exception {
    try {
      // Start one client and three servers
      startVMs(1, 3);

      //create the table
      clientSQLExecute(1, "create table app.t1(flight_id int not null primary key, "
        + "aircraft varchar(20),segment_number int not null ) ");
    
      PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
      p.println("1354,Airbus, 11");
      p.println("1355,Boeing, 12");
      p.close();
    
      clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_test_data.txt', null, null, null, 1, 1, 1, 0, null, null)");
    
      //verify data has been imported successfully
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
    
      st.execute("insert into APP.T1 values (1356, 'Airbus', 13)");
    
      st.execute("select count(*) from app.t1");
      ResultSet rs = st.getResultSet();
      assertTrue(rs.next());
      assertEquals("Number of rows in table is incorrect", 3, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Test for defect #51274. 
   * Test import table functionality with lockTable.
   */
  public void testImportTableWithLockObserver_PR_bug51274() throws Exception {
    try {
      Properties props = new Properties();
      props.setProperty(GfxdConstants.MAX_LOCKWAIT, "10000");
      // Start one client a three servers
      startVMs(1, 3, 0, null, props);
      
      serverExecute(1, importLockTableObserverSet);
      serverExecute(2, importLockTableObserverSet);
      serverExecute(3, importLockTableObserverSet);
      clientExecute(1, importLockTableObserverSet);

      //create the table
      clientSQLExecute(1, "create table app.t1(flight_id int not null primary key, "
        + "aircraft varchar(20),segment_number int not null ) ");
      
      PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
      p.println("1354,Airbus, 11");
      p.println("1355,Boeing, 12");
      p.close();
    
      //run the import table in a separate thread
      new Thread(new Runnable() {
        public void run() {
          try {
            clientSQLExecute(1,
            "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_test_data.txt', null, null, null, 1, 1, 1, 0, null, null)");
          } catch (Exception e) {}
        }
      } ).start();
    
      Thread.sleep(5000);
    
      //verify data has been imported successfully
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
    
      boolean lockTimeoutExceptionOccurred = false;
      try {
        st.execute("insert into APP.T1 values (1356, 'Airbus', 13)");
      } catch (SQLException e) {
        if (!"40XL1".equals(e.getSQLState())) throw e;
        lockTimeoutExceptionOccurred = true;
      }
      assertTrue(lockTimeoutExceptionOccurred);
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  
  /**
   * Test for defect #51274. 
   * Test import functionality with lockTable.
   * With invalid data file, verify that the lock on table is released even in case 
   * of exception during import.
   */
  public void testImportTableInvalidDataInFile_PR_bug51274() throws Exception {
    try {
      startVMs(1, 3);

      //create the table
      clientSQLExecute(1, "create table app.t1(flight_id int not null primary key, "
        + "aircraft varchar(20),segment_number int not null ) ");
    
      PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
      p.println("1354,abababababababababAirbus,11");
      p.println("1355,Boeing,12");
      p.close();
    
      boolean exceptionOccurredDuringImport = false;
      try {
        clientSQLExecute(1,
          "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_test_data.txt', null, null, null, 1, 1, 1, 0, null, null)");
      } catch (Exception e) {
        //do nothing. The lock should be released if exception occurs during import.
        exceptionOccurredDuringImport = true;
      }
      assertTrue("Exception should have occurred during import table", exceptionOccurredDuringImport);
    
      //verify data has been imported successfully
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
    
      st.execute("insert into APP.T1 values (1356, 'Airbus', 13)");
    
      st.execute("select count(*) from app.t1");
      ResultSet rs = st.getResultSet();
      assertTrue(rs.next());
      assertEquals("Number of rows in table is incorrect", 1, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Test for defect #51274. 
   * Test multi-threaded import functionality with lockTable and 2 threads.
   * Verify that table lock is released after the 'import table' procedure completes.
   */
  public void testImportTableWithTwoThreads_PR_bug51274() throws Exception {
    try {
      startVMs(1, 3);

      //create the table
      clientSQLExecute(1, "create table app.t1(flight_id int not null primary key, "
        + "aircraft varchar(20),segment_number int not null ) ");
    
      PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
      p.println("1354,Airbus, 11");
      p.println("1355,Boeing, 12");
      p.close();
    
      clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_test_data.txt', null, null, null, 1, 1, 2, 0, null, null)");
    
      //verify data has been imported successfully
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
    
      st.execute("insert into APP.T1 values (1356, 'Airbus', 13)");
    
      st.execute("select count(*) from app.t1");
      ResultSet rs = st.getResultSet();
      assertTrue(rs.next());
      assertEquals("Number of rows in table is incorrect", 3, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Test for defect #51274. 
   * Test multi-threaded import functionality with lockTable and 3 threads with invalid data in the file.
   * Verify that table lock is released after the 'import table' procedure completes.
   */
  public void testMultiThreadedImportTableInvalidDataInFile_PR_bug51274() throws Exception {
    try {
      startVMs(1, 3);

      //create the table
      clientSQLExecute(1, "create table app.t1(flight_id int not null primary key, "
        + "aircraft varchar(20),segment_number int not null ) ");
    
      PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
      p.println("1354,Airbus, 11");
      p.println("1355,Boeingdjffhsfhhababababab, 12");
      p.close();
    
      boolean exceptionOccurredDuringImport = false;
      try {
      clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_test_data.txt', null, null, null, 1, 1, 3, 0, null, null)");
      } catch (Exception e) {
        //do nothing. The lock should be released if exception occurs during import.
        exceptionOccurredDuringImport = true;
      }
      assertTrue("Exception should have occurred during import table", exceptionOccurredDuringImport);
      
      //verify data has been imported successfully
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();

      st.execute("insert into APP.T1 values (1356, 'Airbus', 13)");

      st.execute("select count(*) from app.t1");
      ResultSet rs = st.getResultSet();
      assertTrue(rs.next());
      // for transactions, the import exception should fail full import
      if (isTransactional) {
        assertEquals("Number of rows in table is incorrect", 1, rs.getInt(1));
      }
      else {
        assertEquals("Number of rows in table is incorrect", 2, rs.getInt(1));
      }
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Test for defect #51274. 
   * Test multi-threaded import functionality with lockTable and more than 2 threads.
   * Verify that table lock is released after the 'import table' procedure completes.
   */
  public void testImportTableWithMoreThanTwoThreads_PR_bug51274() throws Exception {
    try {
      startVMs(1, 3);

      //create the table
      clientSQLExecute(1, "create table app.t1(flight_id int not null primary key, "
        + "aircraft varchar(20),segment_number int not null ) ");
    
      PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
      p.println("1354,Airbus, 11");
      p.println("1355,Boeing, 12");
      p.println("1356,Airbus, 13");
      p.println("1357,Airbus, 14");
      p.println("1358,Boeing, 15");
      p.println("1359,Boeing, 16");
      p.println("1360,Airbus, 17");
      p.println("1361,Boeing, 18");
      p.println("1362,Airbus, 19");
      p.println("1363,Boeing, 20");
      p.close();
    
      clientSQLExecute(1,
        "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_test_data.txt', null, null, null, 1, 1, 6, 0, null, null)");

      //verify data has been imported successfully
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
    
      //do insert which should succeed which means lock on the table is released properly
      st.execute("insert into APP.T1 values (1364, 'Airbus', 21)");
    
      st.execute("select count(*) from app.t1");
      ResultSet rs = st.getResultSet();
      assertTrue(rs.next());
      assertEquals("Number of rows in table is incorrect", 11, rs.getInt(1));
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
  
  /**
   * Runnable to set the Observer for importLockTable
   */
  SerializableRunnable importLockTableObserverSet = new SerializableRunnable(
    "Set importLockTableObserver") {
    @Override
    public void run() throws CacheException {
      GemFireXDQueryObserverHolder.setInstance(importLockTableObserver);
    }
  };
  
  /**
   * Observer implementation class for importLockTable
   */
  final GemFireXDQueryObserver importLockTableObserver = new GemFireXDQueryObserverAdapter() {
    
    @Override
    public void afterLockingTableDuringImport() {
      //sleep for 30 seconds after locking the table during import 
      try {
        Thread.sleep(30000);
      } catch (InterruptedException e) {  }
    }
  };
  
  /**
   * Test for defect #51274. 
   * Test multi-threaded import functionality with lockTable and 6 threads.
   */
  public void testMultithreadedImportTableWithLockObserver_PR_bug51274() throws Exception {
    try {
      Properties props = new Properties();
      props.setProperty(GfxdConstants.MAX_LOCKWAIT, "10000");
      // Start one client a three servers
      startVMs(1, 3, 0, null, props);
      
      serverExecute(1, importLockTableObserverSet);
      serverExecute(2, importLockTableObserverSet);
      serverExecute(3, importLockTableObserverSet);
      clientExecute(1, importLockTableObserverSet);

      //create the table
      clientSQLExecute(1, "create table app.t1(flight_id int not null primary key, "
        + "aircraft varchar(20),segment_number int not null ) ");
      
      PrintWriter p = new PrintWriter(new File("import_test_data.txt"));
      p.println("1354,Airbus, 11");
      p.println("1355,Boeing, 12");
      p.println("1356,Airbus, 13");
      p.println("1357,Airbus, 14");
      p.println("1358,Boeing, 15");
      p.println("1359,Boeing, 16");
      p.println("1360,Airbus, 17");
      p.println("1361,Boeing, 18");
      p.close();
    
      //run the import table in a separate thread
      new Thread(new Runnable() {
        public void run() {
          try {
            clientSQLExecute(1,
            "CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', 'T1', 'import_test_data.txt', null, null, null, 1, 1, 6, 0, null, null)");
          } catch (Exception e) {}
        }
      } ).start();
    
      Thread.sleep(5000);
    
      //verify data has been imported successfully
      Connection conn = TestUtil.getConnection();
      Statement st = conn.createStatement();
    
      boolean lockTimeoutExceptionOccurred = false;
      try {
        st.execute("insert into APP.T1 values (1356, 'Airbus', 13)");
      } catch (SQLException e) {
        if (!"40XL1".equals(e.getSQLState())) throw e;
        lockTimeoutExceptionOccurred = true;
      }
      assertTrue(lockTimeoutExceptionOccurred);
    } finally {
      //delete the import data file
      new File("import_test_data.txt").delete();
    }
  }
}
