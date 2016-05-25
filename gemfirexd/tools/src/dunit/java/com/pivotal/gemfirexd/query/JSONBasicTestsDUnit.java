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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.jdbc.JsonTest;

@SuppressWarnings("serial")
public class JSONBasicTestsDUnit extends DistributedSQLTestBase {

  public JSONBasicTestsDUnit(String name) {
    super(name);
  }
  
  public String getSuffix() {
    return  "";
  }

  public void testJSON() throws Exception {
    startVMs(1, 1, 0, null, null);
    Connection cxn = TestUtil.getConnection();
    boolean isOffHeap = (getSuffix() != "") ;
    JsonTest.simpleJSONOps(cxn, true, isOffHeap);
  }
  
  public void testJSON_replicate() throws Exception {
    startVMs(1, 1, 0, null, null);
    Connection cxn = TestUtil.getConnection();
    boolean isOffHeap = (getSuffix() != "") ;
    JsonTest.simpleJSONOps(cxn, true, isOffHeap);
  }
  
  public void testJSON_thin() throws Exception {
    startVMs(1, 2, 0, null, null);
    
    int clientPort = startNetworkServer(1, null, null);
    Connection cxn = TestUtil.getNetConnection(clientPort, null, null);
    boolean isOffHeap = (getSuffix() != "");
    JsonTest.simpleJSONOps(cxn, true, isOffHeap);
  }  
  
  public void testJSON_TX() throws Exception {
    startVMs(1, 3, 0, null, null);
    Connection cxn = TestUtil.getConnection();
    cxn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    cxn.setAutoCommit(false);
    boolean isOffHeap = (getSuffix() != "");
    JsonTest.simpleJSONOps(cxn, true, isOffHeap);
    cxn.commit();
  }
  
  public void testJSON_thin_TX() throws Exception {
    startVMs(1, 2, 0, null, null);
    
    int clientPort = startNetworkServer(1, null, null);
    Connection cxn = TestUtil.getNetConnection(clientPort, null, null);
    cxn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
    cxn.setAutoCommit(false);
    boolean isOffHeap = (getSuffix() != "");
    JsonTest.simpleJSONOps(cxn, true, isOffHeap);
    cxn.commit();
  } 
  
  public void testJSON_exportImport() throws Exception {
    startVMs(1, 3, 0, null, null);
    Connection cxn = TestUtil.getConnection();

    Statement stmt = cxn.createStatement();

    String[] jsonStrings = new String[4];
    jsonStrings[0] = "{\"f1\":1,\"f2\":true}";
    jsonStrings[1] = "{\"f3\":1,\"f4\":true}";
    jsonStrings[2] = "{\"f5\":1,\"f6\":true}";
    jsonStrings[3] = null;

    stmt.execute("CREATE table app.t1(col1 int, col2 json) persistent "
        + "partition by (col1) " + getSuffix());
    PreparedStatement ps = cxn.prepareStatement("insert into app.t1 "
        + "values (?, ?)");
    for (int i = 0; i < 4; i++) {
      ps.setInt(1, i);
      ps.setString(2, jsonStrings[i]);
      ps.execute();
    }

    try {
      // export data
      stmt.execute("CALL SYSCS_UTIL.EXPORT_TABLE_LOBS_TO_EXTFILE('APP','T1'"
          + ", 'T1.dat',',','\"','UTF-8', 'LobColumn.dat' )");

      // import back into a table and verify
      stmt.execute("CREATE table app.t2(col1 int, col2 json) persistent "
          + "partition by (col1)");
      stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE_LOBS_FROM_EXTFILE('APP', "
          + "'T2', 'T1.dat', ',', '\"', 'UTF-8', 0, 0, 1, 0, null, null)");

      ResultSet rs = stmt.executeQuery("select col1, col2 from app.t2 order"
          + " by col1");
      int i = 0;
      String[] jsonCol = new String[4];
      while (rs.next()) {
        jsonCol[i] = rs.getString(2);
        System.out.println(jsonCol[i]);
        getLogWriter().info(jsonCol[i]);

        if (jsonCol[i] != null) {
          // removing all spaces, \n chars and then comparing
          assertEquals(jsonStrings[i].replaceAll("\\s+", ""),
              jsonCol[i].replaceAll("\\s+", ""));
        } else {
          assertTrue(jsonStrings[i] == null);
        }
        i++;
      }
    } finally {
      new File("T1.dat").delete();
      new File("LobColumn.dat").delete();
    }
  }
  
  public void testJSON_CTAS() throws Exception {
    startVMs(1, 3, 0, null, null);
    Connection cxn = TestUtil.getConnection();
    
    Statement stmt = cxn.createStatement();
    
    String[] jsonStrings = new String[4];
    jsonStrings[0] = "{\"f1\":1,\"f2\":true}";
    jsonStrings[1] = "{\"f3\":1,\"f4\":true}";
    jsonStrings[2] = "{\"f5\":1,\"f6\":true}";
    jsonStrings[3] = null;
    
    stmt.execute("CREATE table app.t1(col1 int, col2 json) persistent " +
            "partition by (col1) " + getSuffix());
    PreparedStatement ps = cxn.prepareStatement("insert into app.t1 " +
            "values (?, ?)");
    for (int i = 0; i < 4; i++) {
      ps.setInt(1, i);
      ps.setString(2, jsonStrings[i]);
      ps.execute();
    }
    
    stmt.execute("CREATE table app.t2 as select * from app.t1 with no data persistent " + getSuffix());
    stmt.execute("insert into app.t2 select * from app.t1");
    
    ResultSet rs = stmt.executeQuery("select col1, col2 from app.t2 order" +
            " by col1");
    int i = 0;
    String[] jsonCol = new String[4];
    while (rs.next()) {
      jsonCol[i] = rs.getString(2);
      System.out.println(jsonCol[i]);
      getLogWriter().info(jsonCol[i]);
      
      if (jsonCol[i] != null) {
        // removing all spaces, \n chars and then comparing
        assertEquals(jsonStrings[i].replaceAll("\\s+", ""),
            jsonCol[i].replaceAll("\\s+", ""));
      } else {
        assertTrue(jsonStrings[i] == null);
      }
      i++;
    }
  }
  
  public void testJSON_HDFS() throws Exception {
    startVMs(1, 3, 0, null, null);

    Connection cxn = TestUtil.getConnection();
    Statement stmt = cxn.createStatement();

    stmt.execute("drop hdfsstore if exists testJSON_HDFS");
    new File("./testJSON_HDFS").delete();
    
    stmt.execute("create hdfsstore testJSON_HDFS namenode "
        + "'localhost' homedir './testJSON_HDFS' ");
    stmt.execute("CREATE table app.t1(col1 int, col2 json) persistent "
        + "partition by (col1) HDFSSTORE (testJSON_HDFS) " + getSuffix());

    String[] jsonStrings = { "{\"f1\":1,\"f2\":true}",
        "{\"f1\":1,\"f2\":true}", "{\"f3\":1,\"f4\":true}",
        "{\"f5\":1,\"f6\":true}", null, "{\"f6\":1,\"f7\":true}" };

    PreparedStatement pstmt = cxn.prepareStatement("insert into app.t1 "
        + "values (?, ?)");
    for (int i = 0; i <= 5; i++) {
      pstmt.setInt(1, i);
      pstmt.setString(2, jsonStrings[i]);
      pstmt.addBatch();
    }
    pstmt.executeBatch();

    ResultSet rs = stmt.executeQuery("select * from --query-HDFS=true\n "
        + "app.t1 order by col1");
    int i = 0;
    while (rs.next()) {
      String jsonCol = rs.getString(2);
      // Clob c = rs.getClob(2);
      // long len = c.length();
      // String jsonCol = c.getSubString(1, (int) len);
      getLogWriter().info(jsonCol);

      if (jsonCol != null) {
        // removing all spaces, \n chars and then comparing
        assertEquals(jsonStrings[i].replaceAll("\\s+", ""),
            jsonCol.replaceAll("\\s+", ""));
      } else {
        assertTrue(jsonStrings[i] == null);
      }
      i++;
    }
    assertEquals(6, i);
    stmt.execute("drop table app.t1");
    stmt.execute("drop hdfsstore testJSON_HDFS");
    new File("./testJSON_HDFS").delete();
  }
  
  public void testJSON_stream() throws Exception {
    startVMs(1, 3, 0, null, null);

    //TODO: use a large JSON data file
    String data[] = { "{\"f1\":1,\"f2\":true}" ,
                       "{\"f2\":2,\"f3\":true}" };
    PrintWriter p = new PrintWriter(new File("jsondata.dat"));
    p.write(data[0]);
    p.close();
    
    p = new PrintWriter(new File("jsondata2.dat"));
    p.write(data[1]);
    p.close();

    Connection cxn = TestUtil.getConnection();
    Statement stmt = cxn.createStatement();

    stmt.execute("CREATE table app.t1(col1 int, col2 json) persistent "
        + "partition by (col1) " + getSuffix());

    PreparedStatement pstmt = cxn.prepareStatement("insert into app.t1 "
        + "values (?, ?)");

    //set ascii stream
    pstmt.setInt(1, 0);
    File file = new File("jsondata.dat");
    int fileLength = (int) file.length();
    FileInputStream fileInputStream = new FileInputStream(file);
    InputStream stream = (InputStream) fileInputStream;
    pstmt.setAsciiStream(2, stream, fileLength);
    pstmt.execute();
    
    //set character stream
    pstmt.setInt(1, 1);
    File file2 = new File("jsondata2.dat");
    long fileLength2 = file2.length();
    BufferedReader bufferedReader = new BufferedReader(new FileReader(file2));
    Reader fileReader = (Reader) bufferedReader;
    pstmt.setCharacterStream(2, fileReader, fileLength2);
    pstmt.execute();

    ResultSet rs = stmt
        .executeQuery("select * from  " + "app.t1 order by col1");
    int i = 0;
    while (rs.next()) {
      Reader r;
      InputStream is;
      StringBuffer sb = new StringBuffer();
      
      if (i == 0) {
        r = rs.getCharacterStream(2);
        int c = -1;
        while ( (c = r.read()) > -1) {
          sb.append((char)c);
        }
      } else {
        is = rs.getAsciiStream(2);
        int c = -1;
        while ( (c = is.read()) > -1) {
          sb.append((char)c);
        }
      }

      String jsonCol = sb.toString();
      getLogWriter().info(jsonCol);

      if (jsonCol != null) {
        // removing all spaces, \n chars and then comparing
        assertEquals(data[i].replaceAll("\\s+", ""),
            jsonCol.replaceAll("\\s+", ""));
      } else {
        assertTrue(data[i] == null);
      }
      i++;
    }
    assertEquals(2, i);
  }
  
  public void testTriggers() throws Exception {
    // Start one client a four servers
    startVMs(1, 3, 0, null, null);
    Connection cxn = TestUtil.getConnection();
    
    Statement stmt = cxn.createStatement();
    
    String[] jsonStrings = new String[4];
    jsonStrings[0] = "{\"f1\":1,\"f2\":true}";
    jsonStrings[1] = "{\"f3\":1,\"f4\":true}";
    jsonStrings[2] = "{\"f5\":1,\"f6\":true}";
    jsonStrings[3] = null;
    
    stmt.execute("CREATE table app.t1(col1 int, col2 json) persistent "
        + "partition by (col1) " + getSuffix());
    stmt.execute("CREATE table app.t1_hist1(col1 int, col2 json) persistent "
        + "partition by (col1) " + getSuffix());
    stmt.execute("CREATE table app.t1_hist2(col1 int, col2 json) persistent "
        + "partition by (col1) " + getSuffix());
    stmt.execute("CREATE table app.t1_hist3(col1 int, col2 json) persistent "
        + "partition by (col1) " + getSuffix());
    
    String triggerStmnt = "CREATE TRIGGER trig1 "
        + "AFTER UPDATE ON app.t1 " + "REFERENCING NEW AS NEWROW "
        + "FOR EACH ROW MODE DB2SQL "
        + "INSERT INTO app.t1_hist1 VALUES "
        + "(NEWROW.COL1, NEWROW.COL2)";
    stmt.execute(triggerStmnt);

    triggerStmnt = "CREATE TRIGGER trig2 " + "AFTER INSERT ON app.t1 "
        + "REFERENCING NEW AS NEWROW " + "FOR EACH ROW MODE DB2SQL "
        + "INSERT INTO app.t1_hist2 VALUES "
        + "(NEWROW.COL1, NEWROW.COL2)";
    stmt.execute(triggerStmnt);

    
    triggerStmnt = "CREATE TRIGGER trig3 " + "AFTER DELETE ON app.t1 "
    + "REFERENCING OLD AS OLDROW " + "FOR EACH ROW MODE DB2SQL "
    + "INSERT INTO app.t1_hist3 VALUES "
    + "(OLDROW.COL1, OLDROW.COL2)";
    stmt.execute(triggerStmnt);
    
    //insert trigger
    PreparedStatement ps = cxn.prepareStatement("insert into app.t1 "
        + "values (?, ?)");
    for (int i = 0; i < 4; i++) {
      ps.setInt(1, i);
      ps.setString(2, jsonStrings[i]);
      ps.execute();
    }
    // check whether insert trigger has added data to app.t1_hist2
    verifyTableData(stmt, "app.t1_hist2", "app.t1");
    
    //update
    PreparedStatement ps2 = cxn.prepareStatement("update app.t1 "
        + "set col2 = ? where col1 = ?");
    for (int i = 0; i < 4; i++) {
      String jsonData = "{\"field\":" + i + ", \"value\":true}";
      ps2.setString(1, jsonData);
      ps2.setInt(2, i);
      ps2.execute();
    }
    // check whether update trigger has added data to app.t1_hist1
    verifyTableData(stmt, "app.t1_hist1 ", "app.t1");
    
    //delete
    PreparedStatement ps3 = cxn.prepareStatement("delete from app.t1 "
        + "where col1 = ?");
    for (int i = 0; i < 4; i++) {
      ps3.setInt(1, i);
      ps3.execute();
    }
    // check whether delete trigger has added data to app.t1_hist3
    verifyTableData(stmt, "app.t1_hist1 ", "app.t1_hist3");
    
    stmt.execute("drop trigger trig1");
    stmt.execute("drop trigger trig2");
    stmt.execute("drop trigger trig3");
  }

  private void verifyTableData(Statement stmt, String table1, String table2)
      throws SQLException {
    ResultSet rs1 = stmt.executeQuery("select * from " + table1);
    Map<Integer, String> m1 = new HashMap<Integer, String>();
    while (rs1.next()) {
      m1.put(rs1.getInt(1), rs1.getString(2));
    }
    rs1.close();

    ResultSet rs2 = stmt.executeQuery("select * from " + table2);
    Map<Integer, String> m2 = new HashMap<Integer, String>();
    while (rs2.next()) {
      m2.put(rs2.getInt(1), rs2.getString(2));
    }
    rs2.close();
//    System.out.println("m1= " + m1);
//    System.out.println("m2= " + m2);
    assertTrue(m1.equals(m2));
  }
  
  public void testAlterTable() throws Exception {
    // Start one client a four servers
    startVMs(1, 3, 0, null, null);
    Connection cxn = TestUtil.getConnection();
    Statement stmt = cxn.createStatement();
    
    stmt.execute("CREATE table app.t1(col1 int, col2 int) persistent " +
        "partition by (col1) " + getSuffix());
    PreparedStatement ps = cxn.prepareStatement("insert into app.t1 " +
        "values (?, ?)");
    for (int i = 0; i < 4; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.execute();
    }
    
    // add json column
    stmt.execute("alter table app.t1 add column col3 json");
    // validate table data 
    ResultSet rs = stmt.executeQuery("select * from app.t1 order by col1");
    int i = 0;
    while (rs.next()) {
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
      assertEquals(null, rs.getObject(3));
      i++;
    }
    assertTrue(i == 4);
    
    String[] jsonStrings = { "{\"f1\":1,\"f2\":true}",
        "{\"f1\":1,\"f2\":true}", "{\"f3\":1,\"f4\":true}",
        "{\"f5\":1,\"f6\":true}", null, "{\"f6\":1,\"f7\":true}" };
    //add data to existing rows
    ps = cxn.prepareStatement("update app.t1 set col3 = ? where col1 = ?");
    for (i = 0; i < 4; i++) {
      ps.setObject(1, jsonStrings[i]);
      ps.setInt(2, i);
      ps.execute();
    }
    //validate
    rs = stmt.executeQuery("select * from app.t1 order by col1");
    i = 0;
    while (rs.next()) {
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
      
      String jsonCol = rs.getString(3);
      if (jsonCol != null) {
        // removing all spaces, \n chars and then comparing
        assertEquals(jsonStrings[i].replaceAll("\\s+", ""),
            jsonCol.replaceAll("\\s+", ""));
      } else {
        assertTrue(jsonStrings[i] == null);
      }
      i++;
    }
    assertTrue(i == 4);
    rs.close();
    ps.close();
    
    // add new rows
    ps = cxn.prepareStatement("insert into app.t1 " +
        "values (?, ?, ?)");
    for (i = 4; i < 6; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i);
      ps.setObject(3, jsonStrings[i]);
      ps.execute();
    }
    //validate
    rs = stmt.executeQuery("select * from app.t1 order by col1");
    i = 0;
    while (rs.next()) {
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
      
      String jsonCol = rs.getString(3);
      if (jsonCol != null) {
        // removing all spaces, \n chars and then comparing
        assertEquals(jsonStrings[i].replaceAll("\\s+", ""),
            jsonCol.replaceAll("\\s+", ""));
      } else {
        assertTrue(jsonStrings[i] == null);
      }
      i++;
    }
    assertTrue(i == 6);
    
    // drop json column
    stmt.execute("alter table app.t1 drop column col3");
    // validate table data 
    rs = stmt.executeQuery("select * from app.t1 order by col1");
    i = 0;
    assertEquals(2, rs.getMetaData().getColumnCount());
    while (rs.next()) {
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
      i++;
    }
    assertTrue(i == 6);
    
    // add json column
    stmt.execute("alter table app.t1 add column col3 json");
    // validate table data 
    rs = stmt.executeQuery("select * from app.t1 order by col1");
    i = 0;
    while (rs.next()) {
      assertEquals(i, rs.getInt(1));
      assertEquals(i, rs.getInt(2));
      assertEquals(null, rs.getString(3));
      assertEquals(null, rs.getObject(3));
      i++;
    }
    assertTrue(i == 6);
  }

  // This test should be removed as these restrictions have been removed
  public void testUnsupportedClauses() throws Exception {
    startVMs(1, 3, 0, null, null);
    Connection cxn = TestUtil.getConnection();
    Statement stmt = cxn.createStatement();

    try {
      stmt.execute("CREATE table app.t1(col1 int, col2 json) persistent " +
          "partition by (col2) " + getSuffix());
      fail("Test should have failed as partitioning on json column " +
          "is not allowed");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("0A000")) {
        throw se;
      }
    }

    try {
      stmt.execute("CREATE table app.t1(col1 int, col2 json) persistent " +
          "partition by list (col2) (VALUES ('nasdaq','nye'), " +
          "VALUES ('amex','lse'), VALUES ('fse','hkse','tse')) " + getSuffix());
      fail("Test should have failed as partitioning on json column " +
          "is not allowed");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("0A000")) {
        throw se;
      }
    }

    try {
      stmt.execute("CREATE table app.t1(col1 int, col2 json primary key)" +
          " persistent partition by (col1) " + getSuffix());
      fail("Test should have failed as as json column " +
          "cannot be a PK");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("42832")) {
        throw se;
      }
    }

    try {
      stmt.execute("CREATE table app.t1(col1 int, col2 json)" +
          " persistent partition by (col1) " + getSuffix());
      stmt.execute("create index app.t1_index on app.t1(col2)");
      fail("Test should have failed as as an index cannot be " +
          "created on a json column");
    } catch (SQLException se) {
      if (!se.getSQLState().equals("X0X67")) {
        throw se;
      }
    }
  }

  public void test51287() throws Exception {
    // Start one client and 3 servers
    startVMs(1, 3, 0, null, null);
    Connection cxn = TestUtil.getConnection();
    Statement stmt = cxn.createStatement();
    
    String createTable = "create table trade.customers (cid int not null, " +
    		"networth_json json, json_details json, primary key (cid) )   " +
    		"partition by range (cid) ( VALUES " +
    		"BETWEEN 0 AND 999, VALUES BETWEEN 1000 AND 1102, VALUES " +
    		"BETWEEN 1103 AND 1250, VALUES BETWEEN 1251 AND 1677, " +
    		"VALUES BETWEEN 1678 AND 10000) " + getSuffix();
    
    stmt.execute(createTable);
    PreparedStatement ps = cxn.prepareStatement("insert into trade.customers" +
    		"(cid, json_details) values (?, ?)");
    String jsonString1 = "{\"f1\":1,\"f2\":true}";
    for (int i = 0; i < 4; i++) {
      ps.setInt(1, i*100);
      ps.setString(2, jsonString1);
      ps.execute();
    }
    ps = cxn.prepareStatement("update trade.customers set networth_json = ? where " +
    		"cid = ?");
    String jsonString = "{\"networth\":[{\"availloan\":50000,\"cash\":53688," +
    		"\"loanlimit\":50000,\"tid\":2," +
    		"\"securities\":0,\"cid\":200}]}";
    ps.setString(1, jsonString);
    ps.setInt(2, 200);
    ps.execute();
    
    ps.setString(1, null);
    ps.setInt(2, 300);
    ps.execute();
    
    stmt.execute("select * from trade.customers where cid = 200");
    ResultSet rs = stmt.getResultSet();
    assertTrue(rs.next());
    String output = rs.getString(2);
    getLogWriter().info("networth_json = " + output);
    // removing all spaces, \n chars and then comparing
    assertEquals(jsonString.replaceAll("\\s+", ""),
        output.replaceAll("\\s+", ""));
    
    stmt.execute("select * from trade.customers where cid = 300");
    rs = stmt.getResultSet();
    assertTrue(rs.next());
    output = rs.getString(2);
    assertEquals(output, null);
  }
}
