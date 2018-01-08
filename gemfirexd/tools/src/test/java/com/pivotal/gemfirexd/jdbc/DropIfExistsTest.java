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

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import com.gemstone.gemfire.internal.AvailablePort;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.procedure.ProcedureExecutionContext;

import junit.framework.TestSuite;
import junit.textui.TestRunner;
/**
 * Tests for DROP with IF EXISTS 
 * @author sjigyasu
 *
 */
public class DropIfExistsTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(DropIfExistsTest.class));
  }
  
  public DropIfExistsTest(String name) {
    super(name);
  }
  
  public void testDropTableIfExists() throws SQLException {
    setupConnection();

    sqlExecute("CREATE SCHEMA EMP",false);
    
    sqlExecute("CREATE TABLE EMP.AVAILABILITY2" +
                "(ID INT PRIMARY KEY," +
                "BOOKING_DATE DATE NOT NULL," +
                "ROOMS_TAKEN INT)", false);
    
    sqlExecute("DROP TABLE IF EXISTS EMP.AVAILABILITY2", false);
    try{
      sqlExecute("DROP TABLE EMP.AVAILABILITY2", false);
    }
    catch(SQLException e){
      // SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    sqlExecute("DROP TABLE IF EXISTS APP.NOSUCHTABLE", false);
    sqlExecute("DROP TABLE IF EXISTS NOSUCHSCHEMA.NOSUCHTABLE", false);
  }
  public void testDropSchemaIfExists() throws SQLException {

    setupConnection();

    //1. Test IF EXISTS for existing empty schema
    
    // verify that the schema region "EMP" doesn't exist
    assertNull(Misc.getRegionForTable("EMP", false));
    sqlExecute("CREATE SCHEMA EMP", true);
    // verify that the schema region "EMP" exists
    assertNotNull(Misc.getRegionForTable("EMP", false));
    // drop the schema if it exists 
    sqlExecute("DROP SCHEMA IF EXISTS EMP RESTRICT", true);
    

    //2. Test IF EXISTS for existing non-empty schema
    
    // verify that the schema region "EMP" doesn't exist
    assertNull(Misc.getRegionForTable("EMP", false));
    sqlExecute("CREATE SCHEMA EMP", true);
    sqlExecute("CREATE TABLE EMP.AVAILABILITY "
        + "(HOTEL_ID INT NOT NULL, BOOKING_DATE DATE NOT NULL, "
        + "ROOMS_TAKEN INT, CONSTRAINT HOTELAVAIL_PK PRIMARY KEY "
        + "(HOTEL_ID, BOOKING_DATE))", false);
    assertNotNull(Misc.getRegionForTable("EMP", false));
    // drop the schema if it exists
    // Should throw exception since the schema is not empty
    try{
    sqlExecute("DROP SCHEMA IF EXISTS EMP RESTRICT", true);
    }
    catch(SQLException e){
      // SQLState.LANG_SCHEMA_NOT_EMPTY
      if(!e.getSQLState().equalsIgnoreCase("X0Y54")){
        throw e;
      }
    }
    // drop the table
    sqlExecute("drop table EMP.AVAILABILITY", true);
    // Try dropping schema again. 
    sqlExecute("DROP SCHEMA IF EXISTS EMP RESTRICT", true);
    assertNull(Misc.getRegionForTable("EMP", false));
    
    //3. Test IF EXISTS for non-existent empty schema
    // This should not throw error
    sqlExecute("DROP SCHEMA IF EXISTS EMPXX RESTRICT", true);
    
  }

  public void testBug51005() throws SQLException {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Statement st = conn.createStatement();
    st.execute("create table test(col1 int not null, col2 int not null) partition by column(col2)");
    st.execute("create index idx on test(col2)");
    st.execute("drop index if exists idx");
    st.execute("drop index if exists idx");
    try {
      st.execute("drop index idx");
      fail("The above statement should have thrown an exception - 1");
    }
    catch(SQLException ex) {
      if(!ex.getSQLState().equals("42X65")){
        throw ex;
      }
    }
    st.execute("drop index if exists idx");
    
    st.execute("create index idx on test(col2)");
    st.execute("drop index if exists idx");  //
    try {
      st.execute("drop index idx");
      fail("The above statement should have thrown an exception - 2");
    } catch(SQLException ex) {
      if(!ex.getSQLState().equals("42X65")){
        throw ex;
      }
    }
  }

  // Verify a similar pattern of the bug for "drop table if exists"
  public void testBug51005_2() throws SQLException {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Statement st = conn.createStatement();
    st.execute("create table test(col1 int not null, col2 int not null) partition by column(col2)");

    st.execute("drop table if exists sch.test");
    st.execute("drop table if exists sch.test");
    st.execute("drop table if exists sch.test");
    st.execute("drop table if exists test");
    try {
      st.execute("drop table test");
      fail("The above statement should have thrown an exception - 1");
    } catch (SQLException e) {
      if (!e.getSQLState().equals("42Y55")) {
        throw e;
      }
    }
    st.execute("drop table if exists sch.test");
    st.execute("create table test(col1 int not null, col2 int not null) partition by column(col2)");
    st.execute("drop table if exists test");
    try {
      st.execute("drop table test");
      fail("The above statement should have thrown an exception - 2");
    } catch (SQLException e) {
      if (!e.getSQLState().equals("42Y55")) {
        throw e;
      }
    }
  }

  
  public void testDropIndexIfExists() throws SQLException{
    setupConnection();
    sqlExecute("CREATE SCHEMA EMP",false);
    
    sqlExecute("CREATE TABLE EMP.AVAILABILITY2" +
    		"(ID INT PRIMARY KEY," +
    		"BOOKING_DATE DATE NOT NULL," +
    		"ROOMS_TAKEN INT)", false);
    
    sqlExecute("CREATE INDEX EMP.IDX_ID ON EMP.AVAILABILITY2(ID)", false);
    
    // Regular drop index should work
    sqlExecute("DROP INDEX EMP.IDX_ID", false);
    
    // Dropping non-existent index without IF EXISTS should throw error.
    try{
      sqlExecute("DROP INDEX EMP.ABC", false);
    }
    catch(SQLException e){
      //SQLState.LANG_INDEX_NOT_FOUND
      if(!e.getSQLState().equals("42X65")){
        throw e;
      }
    }

    // Dropping non-existent index with IF EXISTS should not throw error
    sqlExecute("DROP INDEX IF EXISTS EMP.DEF", false);
    
    // Create index again
    sqlExecute("CREATE INDEX EMP.IDX_ID ON EMP.AVAILABILITY2(ID)", false);

    // Test dropping of existing index. 
    sqlExecute("DROP INDEX IF EXISTS EMP.IDX_ID", false);

    // Finally drop the table and schema
    sqlExecute("DROP TABLE IF EXISTS EMP.AVAILABILITY2", false);
    
    sqlExecute("DROP SCHEMA IF EXISTS EMP RESTRICT", false);
    
    // Drop index from a non-existent schema
    sqlExecute("DROP INDEX IF EXISTS TESTSCHEMA.IDXXXX", false);
    

    // TODO:
    // Test that the indexes created indirectly as a result of constraints are dropped alright
    // and that their dropping isn't affected by the changes for "if exists"
    
/*    sqlExecute("CREATE TABLE EMP.TESTCONSTRAINT(C1 INT PRIMARY KEY, C2 DATE, C3 INT, UNIQUE(C3))", false);
    Statement statement = jdbcConn.createStatement();
    statement.execute("SELECT COUNT(*) AS COUNT FROM SYS.INDEXES WHERE SCHEMANAME='EMP' AND TABLENAME='TESTCONSTRAINT'");
    ResultSet rs = statement.getResultSet();
    while(rs.next()){
      assertEquals(2, rs.getInt(1));
    }
    
    sqlExecute("DROP TABLE EMP.TESTCONSTRAINT", false);
    statement.execute("SELECT COUNT(*) AS COUNT FROM SYS.INDEXES WHERE SCHEMANAME='EMP' AND TABLENAME='TESTCONSTRAINT'");
    rs = statement.getResultSet();
    while(rs.next()){
      assertEquals(0, rs.getInt(1));
    }
*/    
  }
  public void testDropProcedureIfExists() throws SQLException{
    setupConnection();
    // Create a procedure in the default schema
    String createProc = "CREATE PROCEDURE EXAMPLE_PROC(IN S_MONTH INTEGER," +
        "IN S_YEAR INTEGER, OUT TOTAL DECIMAL(10,2)) " +
         "LANGUAGE JAVA PARAMETER STYLE JAVA READS SQL DATA EXTERNAL NAME " + 
         "'com.pivotal.gemfirexd.jdbc.DropIfExistsTest.exampleProc'";
    sqlExecute(createProc, false);
    
    // Drop with if exists - should not throw error
    sqlExecute("DROP PROCEDURE IF EXISTS EXAMPLE_PROC", false);

    // Drop without if exists - should throw error
    try{
    sqlExecute("DROP PROCEDURE EXAMPLE_PROC", false);
    }
    catch(SQLException e){
      //SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    // Drop a non-existent procedure in existing schema, with if exists - should not throw error.
    sqlExecute("DROP PROCEDURE IF EXISTS APP.PROCXXX", false);

    // Drop non-existent procedure without if exists - should throw error
    try{
    sqlExecute("DROP PROCEDURE APP.PROCXXX", false);
    }
    catch(SQLException e){
      //SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    
    // Drop a non-existent procedure in a non-existent schema - should not throw error
    sqlExecute("DROP PROCEDURE IF EXISTS TESTXX.PROCXXX", false);
  }
  public static void exampleProc(Integer inParam1, Integer inParam2,
      Float total, ProcedureExecutionContext ctx) throws SQLException {
  }
  public void testDropFunctionIfExists() throws SQLException{
    setupConnection();
    // Create a function in the default schema
    sqlExecute("CREATE FUNCTION R() RETURNS DOUBLE EXTERNAL NAME"
            + "'java.lang.Math.random' LANGUAGE JAVA PARAMETER STYLE JAVA", false);
    
    // Drop with if exists - should drop without error
    sqlExecute("DROP FUNCTION IF EXISTS R", false);


    // Drop without if exists - should throw error
    try{
    sqlExecute("DROP FUNCTION R", false);
    }
    catch(SQLException e){
      //SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    // Drop a non-existent function in existing schema, with if exists - should not throw error.
    sqlExecute("DROP FUNCTION IF EXISTS APP.FUNCXXX", false);

    // Drop a non-existent function in a non-existent schema - should not throw error
    sqlExecute("DROP FUNCTION IF EXISTS TESTXX.FUNCXXX", false);
  }
  public void testDropSynonymIfExists() throws SQLException{
    setupConnection();
    sqlExecute("CREATE TABLE APP.TEMP(C1 INT, C2 INT)", false);

    // Create a synonym in the default schema
    sqlExecute("CREATE SYNONYM SYNTEMP FOR APP.TEMP", false);
    
    // Drop with if exists - should drop without error
    sqlExecute("DROP SYNONYM IF EXISTS APP.SYNTEMP", false);
    
    // Drop without if exists - should throw error
    try{
      sqlExecute("DROP SYNONYM SYNTEMP", false);
    }
    catch(SQLException e){
      //SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    // Drop a non-existent synonym in existing schema, with if exists - should not throw error
    sqlExecute("DROP SYNONYM IF EXISTS APP.NOSYNONYM", false);

    // Drop a non-existent synonym qualified with non-existent schema, with if exists - should not throw error.
    sqlExecute("DROP SYNONYM IF EXISTS NOSCHEMA.NOSYNONYM", false);
  }
  public void testDropAliasIfExists() throws SQLException{
    setupConnection();
    
    // Create an alias in the default schema
    sqlExecute("CREATE ALIAS AL_LISTAGG FOR 'sql.poc.useCase3.LISTAGGPROCESSOR'", false);
    
    // Drop with if exists should drop without error
    sqlExecute("DROP ALIAS IF EXISTS AL_LISTAGG", false);
    
    // Drop without if exists should throw error
    try{
      sqlExecute("DROP ALIAS AL_LISTAGG", false);
    }
    catch(SQLException e){
    //SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    // Drop a non-existent alias in existing schema, with if exists - should not throw error
    sqlExecute("DROP ALIAS IF EXISTS APP.NOALIAS", false);
    
    // Drop a non-existent alias qualified with non-existent schema, with if exists - should not throw error
    sqlExecute("DROP ALIAS IF EXISTS NOSCHEMA.NOALIAS", false);
    
  }
  public void testDropTypeIfExists() throws SQLException{
    setupConnection();
    
    // Create a user defined type in the default schema
    sqlExecute("CREATE TYPE UDT_PRICE EXTERNAL NAME 'org.apache.derbyTesting.functionTests.tests.lang.Price' LANGUAGE JAVA", false);
    
    // Drop with if exists should drop without error
    sqlExecute("DROP TYPE IF EXISTS UDT_PRICE RESTRICT", false);
    
    // Drop without if exists should throw error
    try{
      sqlExecute("DROP TYPE UDT_PRICE RESTRICT", false);
    }
    catch(SQLException e){
      //SQLState.LANG_OBJECT_DOES_NOT_EXIST
        if(!e.getSQLState().equals("42Y55")){
          throw e;
        }
    }
    // Drop a non-existent UDT in existing schema, with if exists - should not throw error
    sqlExecute("DROP TYPE IF EXISTS NOUDT RESTRICT", false);
    
    // Drop a non-existent UDT qualified with non-existent schema, with if exists - should not throw error
    sqlExecute("DROP TYPE IF EXISTS NOSCHEMA.NOUDT RESTRICT", false);
  }
  public void testDropViewIfExists() throws SQLException{
    setupConnection();
    sqlExecute("CREATE SCHEMA T", false);
    sqlExecute("CREATE TABLE T.TESTTABLE(C1 INT PRIMARY KEY, C2 INT)", false);
    
    // Create a view
    sqlExecute("CREATE VIEW T.TESTVIEW AS SELECT * FROM T.TESTTABLE", false);
    
    // Drop view with if exists - should not throw error
    sqlExecute("DROP VIEW IF EXISTS T.TESTVIEW", false);
    
    // Drop view without if exists - should throw error
    try{
      sqlExecute("DROP VIEW T.TESTVIEW", false);
    }
    catch(SQLException e){
      // SQLState.LANG_TABLE_NOT_FOUND_DURING_EXECUTION
      if(!e.getSQLState().equals("X0X05")){
        throw e;
      }
    }
    // Drop a non-existent view qualified with existing schema-name -- should not throw error
    sqlExecute("DROP VIEW IF EXISTS T.NOSUCHVIEW", false);

    // Drop a non-existent view qualified with non-existent schema-name -- should not throw error
    sqlExecute("DROP VIEW IF EXISTS NOSUCHSCHEMA.NOSUCHVIEW", false);
    
  }
  public void testDropTriggerIfExists() throws SQLException{

    setupConnection();
    sqlExecute("CREATE TABLE CURRENT_DATA(ID INT NOT NULL, C1 INT NOT NULL, C2 VARCHAR(12) NOT NULL) REPLICATE", false);
    sqlExecute("CREATE TABLE ARCHIVED_DATA(ID INT NOT NULL, C2 VARCHAR(12) NOT NULL, COMMENT VARCHAR(20) NOT NULL)", false);

    sqlExecute("CREATE TRIGGER testtrigger "
              + "AFTER INSERT ON CURRENT_DATA "
              + "REFERENCING NEW AS NEWROW "
              + "FOR EACH ROW MODE DB2SQL "
              + "INSERT INTO ARCHIVED_DATA VALUES(NEWROW.ID, NEWROW.C2, 'from testtrigger')", false);

    sqlExecute("INSERT INTO CURRENT_DATA VALUES(1, 5, 'test')", false);

    // Dropping trigger with if exists should not throw error.
    sqlExecute("DROP TRIGGER IF EXISTS testtrigger", false);
    
    // Dropping trigger without if exists should throw error
    try{
      sqlExecute("DROP TRIGGER testtrigger", false);
    }
    catch(SQLException e){
      // SQLState.LANG_OBJECT_NOT_FOUND
      if(!e.getSQLState().equals("42X94")){
        throw e;
      }
    }
    // Dropping a non-existent trigger qualified with existing schema name should not throw error with if exists.
    sqlExecute("DROP TRIGGER IF EXISTS APP.NOSUCHTRIGGER", false);
    
    // Dropping a non-existent trigger qualified with non-existent schema-name should not throw error with if exists.
    sqlExecute("DROP TRIGGER IF EXISTS NOSUCHSCHEMA.NOSUCHTRIGGER", false);
  }
  public void testDropGatewaySenderIfExists() throws SQLException{
    Properties props = new Properties();
    props.setProperty("server-groups", "SG1");
    setupConnection(props);
    sqlExecute("CREATE GATEWAYSENDER TESTSENDER ( remotedsid 2 socketbuffersize 1000 "
        + "manualstart true  SOCKETREADTIMEOUT 1000 ENABLEBATCHCONFLATION true "
        + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false MAXQUEUEMEMORY 400  "
        + "ALERTTHRESHOLD 100) server groups (SG1)", false);
    sqlExecute("DROP GATEWAYSENDER IF EXISTS TESTSENDER", false);
    
    try{
      sqlExecute("DROP GATEWAYSENDER mySender", false);
    }
    catch(SQLException e){
      // SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    sqlExecute("DROP GATEWAYSENDER IF EXISTS NOSUCHGATEWAYSENDER", false);
  }
  public void testDropGatewayReceiverIfExists() throws SQLException{
    Properties props = new Properties();
    props.setProperty("server-groups", "SG1");
    setupConnection(props);
    sqlExecute("CREATE GATEWAYRECEIVER TESTRECEIVER (bindaddress 'localhost' " +
        "startport 1530 endport 1541) SERVER GROUPS (SG1)", false);
    sqlExecute("DROP GATEWAYRECEIVER IF EXISTS TESTRECEIVER", false);

    try{
      sqlExecute("DROP GATEWAYRECEIVER TESTRECEIVER", false);
    }
    catch(SQLException e){
      // SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    sqlExecute("DROP GATEWAYRECEIVER IF EXISTS NOSUCHGATEWAYRECEIVER", false);
  }
  public void testDropAsyncEventListenerIfExists() throws SQLException{
    Properties props = new Properties();
    props.setProperty("server-groups", "SG1");
    setupConnection(props);
   
    sqlExecute("CREATE ASYNCEVENTLISTENER TESTLISTENER ( listenerclass "
            + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
            + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
            + "jdbc:derby:newDB;create=true' "
            + "manualstart false) server groups (sg1)", false);
    sqlExecute("DROP ASYNCEVENTLISTENER IF EXISTS TESTLISTENER", false);
    try{
      sqlExecute("DROP ASYNCEVENTLISTENER TESTLISTENER", false);
    }
    catch(SQLException e){
      // SQLState.LANG_OBJECT_DOES_NOT_EXIST
      if(!e.getSQLState().equals("42Y55")){
        throw e;
      }
    }
    sqlExecute("DROP ASYNCEVENTLISTENER IF EXISTS NOSUCHLISTENER", false);
  }
  
  public void testDropDiskStoreIfExists() throws SQLException{
    setupConnection();
    sqlExecute("CREATE DISKSTORE TESTSTORE", false);
    sqlExecute("DROP DISKSTORE IF EXISTS TESTSTORE", false);
    try{
        sqlExecute("DROP DISKSTORE TESTSTORE", false);
      }
      catch(SQLException e){
        // SQLState.LANG_OBJECT_DOES_NOT_EXIST
        if(!e.getSQLState().equals("42Y55")){
          throw e;
        }
      }
      sqlExecute("DROP DISKSTORE IF EXISTS TESTSTORE", false);
  }
}
