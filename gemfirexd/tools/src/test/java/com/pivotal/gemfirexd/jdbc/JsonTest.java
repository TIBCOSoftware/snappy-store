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

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.Reader;
import java.sql.Clob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Properties;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.pivotal.gemfirexd.TestUtil;

public class JsonTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(JsonTest.class));
  }
  
  public JsonTest(String name) {
    super(name);
  }
  
  public static void simpleJSONOps (Connection conn, boolean isPartitioned, boolean isOffHeap) 
      throws Exception {
    Statement stmt = conn.createStatement();
    
    String[] jsonStrings = new String[6];
    jsonStrings[0] = "{\"f1\":1,\"f2\":true}";
    jsonStrings[1] = "{\"f3\":1,\"f4\":true}";
    jsonStrings[2] = "{\"f5\":1,\"f6\":true}";
    jsonStrings[3] = null;
    jsonStrings[4] = "{\"f6\":1,\"f7\":true}";
    jsonStrings[5] = "{\"f7\":1,\"f8\":true}";
    
    String createTable = "CREATE table t1(col1 int, col2 json) persistent";
    if (isPartitioned) {
      createTable = createTable + " partition by (col1)";
    } else {
      createTable = createTable + " replicate";
    }
    
    if (isOffHeap) {
      createTable = createTable + " offheap";
    } 
    
    stmt.execute(createTable);
//    stmt.execute("CREATE table t1(col1 int, col2 varchar(100)) persistent partition by (col1)");
    
    //insert
    stmt.execute("insert into t1 values (1, '" + jsonStrings[0] + "')");

    //prepared stmt, setString
    PreparedStatement ps = conn.prepareStatement("insert into t1 values (?, ?)");
    ps.setInt(1, 2);
    ps.setString(2, jsonStrings[1]);
    ps.execute();

    //prepared stmt, setObject
    PreparedStatement ps2 = conn
        .prepareStatement("insert into t1 values (?, ?)");
    ps2.setInt(1, 3);
    ps2.setObject(2, jsonStrings[2]);
    ps2.execute();

    //prepared stmt, insert null
    ps2.setInt(1, 4);
    ps2.setString(2, jsonStrings[3]);
    ps2.execute();
    
    //put dml
    stmt.execute("put into t1 values (5, '" + jsonStrings[4] + "')");
    
    //setClob
    ps2.setInt(1, 6);
    Clob inputClob = conn.createClob();
    inputClob.setString(1, jsonStrings[5]);
    ps2.setClob(2, inputClob);
    ps2.execute();
    
    // simple order by query
    ResultSet rs = stmt.executeQuery("select col1, col2 from t1 order by col1");
    int i = 0;
    String[] jsonCol = new String[6];
    Clob c = null;
    while (rs.next()) {
//      jsonCol[i] = rs.getString(2);
      c = rs.getClob(2);
//      c = (Clob) rs.getObject(2);
      if (c != null) {
        jsonCol[i] = c.getSubString(1, (int) c.length());
      } else {
        jsonCol[i] = null;
      }
      System.out.println(jsonCol[i]);
      getLogger().info(jsonCol[i]);
      
      if (jsonCol[i] != null) {
        // removing all spaces, \n chars and then comparing
        assertEquals(jsonStrings[i].replaceAll("\\s+", ""),
            jsonCol[i].replaceAll("\\s+", ""));
      } else {
        assertTrue(jsonStrings[i] == null);
      }
      
      i++;
    }
    
    // simple group by query
    rs = stmt.executeQuery("select count(a.col1) from " +
    		"(select col1, col2 from t1) a group by a.col1");
    i = 0;
    while (rs.next()) {
      System.out.println(rs.getInt(1));
      assertEquals(1, rs.getInt(1));
      i++;
    }
    assertEquals(6, i);
    
    //--
    rs = stmt.executeQuery("select count(a.col1) " +
    		"from (select col1, col2 from t1) a group by a.col1" +
    		" having a.col1 > 0");
    i = 0;
    while (rs.next()) {
      System.out.println(rs.getInt(1));
      assertEquals(1, rs.getInt(1));
      i++;
    }
    assertEquals(6, i);

    // needed when run in TX mode for the view creation
    conn.commit();
    // simple order by query on view
    stmt.execute("create view view1 as select * from t1 where col1 > 0");
    rs = stmt.executeQuery("select * from view1 order by col1");
    i = 0;
    jsonCol = new String[6];
    while (rs.next()) {
      jsonCol[i] = rs.getString(2);
      System.out.println(jsonCol[i]);
      getLogger().info(jsonCol[i]);
      
      if (jsonCol[i] != null) {
        // removing all spaces, \n chars and then comparing
        assertEquals(jsonStrings[i].replaceAll("\\s+", ""),
            jsonCol[i].replaceAll("\\s+", ""));
      } else {
        assertTrue(jsonStrings[i] == null);
      }
      i++;
    }
    stmt.execute("drop view view1");
    stmt.execute("drop table t1");
  }
  
  public void testJSON() throws Exception {
    Connection cxn = TestUtil.getConnection();
    simpleJSONOps(cxn, true, false);
  }

  public void testJSON_thinClient() throws Exception {
    Connection conn = TestUtil.getConnection();
    int netPort = TestUtil.startNetserverAndReturnPort();
    Connection cxn = TestUtil.getNetConnection(netPort, null, null);
    
    simpleJSONOps(cxn, true, false);
  }
  
  public void testJSON_stream() throws Exception {
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
        + "partition by (col1)");

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
      
      r = rs.getCharacterStream(2);
      int c = -1;
      while ( (c = r.read()) > -1) {
        sb.append((char)c);
      }
      
//      if (i == 0) {
//        r = rs.getCharacterStream(2);
//        int c = -1;
//        while ( (c = r.read()) > -1) {
//          sb.append(c);
//        }
//      } else {
//        is = rs.getAsciiStream(2);
//        int c = -1;
//        while ( (c = is.read()) > -1) {
//          sb.append(c);
//        }
//      }

      String jsonCol = sb.toString();
      getLogger().info(jsonCol);

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
    stmt.execute("drop table t1");
  }
  
  public void testBug51374_1() throws Exception {
    //Test with json column
    Connection cxn = TestUtil.getConnection();

    Statement stmt = cxn.createStatement();

    String[] jsonStrings = new String[4];
    jsonStrings[0] = "{\"f1\":1,\"f2\":true, \"stringField\": \"mystring\"}";
    jsonStrings[1] = "{\"f3\":1,\"f4\":true, \"stringField\": \"mystring\"}";
    jsonStrings[2] = "{\"f5\":1,\"f6\":true, \"stringField\": \"mystring\"}";
    jsonStrings[3] = null;

    stmt.execute("CREATE table app.t1(col1 int, col2 varchar(10), col3 json) persistent "
        + "partition by (col1) ");
    PreparedStatement ps = cxn.prepareStatement("insert into app.t1 "
        + "values (?, ?, ?)");
    for (int i = 0; i < 4; i++) {
      ps.setInt(1, i);
      ps.setString(2, ""+i);
      ps.setString(3, jsonStrings[i]);
      ps.execute();
    }

    try {
      // export data
      stmt.execute("CALL SYSCS_UTIL.EXPORT_TABLE('APP','T1', 'T1.dat',NULL, NULL, NULL)");

      // import back into a table and verify
      stmt.execute("CREATE table app.t2(col1 int, col2 varchar(10), col3 json) persistent "
          + "partition by (col1)");
      stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', "
          + "'T2', 'T1.dat', NULL, NULL, NULL, 0, 0, 1, 0, null, null)");

      ResultSet rs = stmt.executeQuery("select col1, col3 from app.t2 order"
          + " by col1");
      int i = 0;
      String[] jsonCol = new String[4];
      while (rs.next()) {
        jsonCol[i] = rs.getString(2);
        System.out.println(jsonCol[i]);
        getLogger().info(jsonCol[i]);

        if (jsonCol[i] != null) {
          // removing all spaces, \n chars and then comparing
          assertEquals(jsonStrings[i].replaceAll("\\s+", ""),
              jsonCol[i].replaceAll("\\s+", ""));
        } else {
          assertTrue(jsonStrings[i] == null);
        }
        i++;
      }
      stmt.execute("drop table t1");
      stmt.execute("drop table t2");
    } finally {
      new File("T1.dat").delete();
    }
  }
  
  public void testBug51374_2() throws Exception {
    //Test with varchar column
//    Properties props = new Properties();
//    props.setProperty("log-level", "fine");
//    props.setProperty("gemfirexd.debug.true", "TraceImport");
    Connection cxn = TestUtil.getConnection();

    Statement stmt = cxn.createStatement();

    String s[] = { "a\nbc", "p\"q\"\nr", "", "   \"\n\"\"", "", null }; 

    stmt.execute("CREATE table app.t1(col1 int, col2 varchar(10)) persistent "
        + "partition by (col1) ");
    PreparedStatement ps = cxn.prepareStatement("insert into app.t1 "
        + "values (?, ?)");
    for (int j = 0; j < s.length; j++) {
      ps.setInt(1, j);
      ps.setString(2, s[j]);
      ps.execute();
    }
    
    try {
      // export data
      stmt.execute("CALL SYSCS_UTIL.EXPORT_TABLE('APP','T1', 'T1.dat',NULL, NULL, NULL)");

      // import back into a table and verify
      stmt.execute("CREATE table app.t2(col1 int, col2 varchar(10)) persistent "
          + "partition by (col1)");
      stmt.execute("CALL SYSCS_UTIL.IMPORT_TABLE_EX('APP', "
          + "'T2', 'T1.dat', NULL, NULL, NULL, 0, 0, 2, 0, null, null)");

      ResultSet rs = stmt.executeQuery("select col1, col2 from app.t2 order"
          + " by col1");
      int i = 0;
      while (rs.next()) {
        String output = rs.getString(2);
        assertEquals(s[i], output);
        System.out.println("output=" + output);
        i++;
      }
      stmt.execute("drop table t1");
      stmt.execute("drop table t2");
    } finally {
      new File("T1.dat").delete();
    }
  }
}
