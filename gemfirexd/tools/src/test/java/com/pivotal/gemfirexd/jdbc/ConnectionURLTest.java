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
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;

/**This unit test is to test the connection URL.
 * 
 * <p>
 * <b> Note </b>
 * The requirement on the URL is that the database name is an empty string. Any provided
 * database names are considered as errors and throw an exception. (which exception?)
 *    
 * 
 * <p>Two scenarios are considered in this test
 * <ol>
 *     <li>
 *         the connection with a specified database name.    
 *     </li>
 *     <li>
 *         the connection without the database name.
 *     </li>
 *     <li>
 *          a new connection without database name after the first connection with 
 *          empty database name
 *     </li>
 *     <li>
 *         a new connection database name after the first connection with 
 *          empty database name
 *     </li>
 * </ol>  
 * </p>
 * @author yjing
 *
 */
public class ConnectionURLTest extends JdbcTestBase {

  private static final String protocol = "jdbc:gemfirexd:";

  private static final String dbName = "gemfireCxn";

  private static final String dbName2 = "GemFireXD";

  private static final String dbName3 = "gemfirexd";

  private static final String dbName4 = "GEMFIREXD";

  public ConnectionURLTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(ConnectionURLTest.class));
  }

  /**
   * Test connection without db name. 
   * @throws SQLException
   */

  public void testConnectionURLWithoutName() throws SQLException {

    Connection conn = null;
    Properties props = new Properties();
    props.setProperty("mcast-port", "0");

    conn = DriverManager.getConnection(protocol +
        ";host-data=true;create=true", props);

    Statement s = conn.createStatement();
    s.execute("create table t1 (c1 int primary key, c2 char(200))");
    s.execute("insert into t1 (c1, c2) values (10, 'YYYY')");
    s.execute("insert into t1 (c1, c2) values (20, 'YYYY')");
    s.executeQuery("select * from t1 where t1.c1=10");

    conn.close();
  }

  /***Test connection with  db name;
   * 
   * @throws SQLException
   */
  public void testConnectionURLWithName() throws SQLException {
    Properties props = new Properties();
    props.setProperty("mcast-port", "0");

    checkExceptionForURL(protocol + dbName + ";host-data=true", props);

    // don't fail with dbname "gemfirexd" or upper-case variants
    Connection conn = DriverManager.getConnection(protocol + dbName2
        + ";host-data=true;create=true;mcast-port=0");

    Statement s = conn.createStatement();
    ResultSet rs = s.executeQuery("select id from sys.members");
    assertTrue(rs.next());
    assertFalse(rs.next());
    conn.close();

    conn = DriverManager.getConnection(protocol + dbName3
        + ";host-data=true;create=true;mcast-port=0");

    s = conn.createStatement();
    rs = s.executeQuery("select id from sys.members");
    assertTrue(rs.next());
    assertFalse(rs.next());
    conn.close();

    conn = DriverManager.getConnection(protocol + dbName4
        + ";host-data=true;create=true;mcast-port=0");

    s = conn.createStatement();
    rs = s.executeQuery("select id from sys.members");
    assertTrue(rs.next());
    assertFalse(rs.next());
    conn.close();
  }

  /***Test multi connection without  db name;
   * 
   * @throws SQLException
   */
  public void testMultiConnectionURLWithoutName() throws SQLException {

    Connection conn = null;
    Properties prop = new Properties();
    prop.setProperty("host-data", "true");
    prop.setProperty("mcast-port", "0");

    conn = DriverManager.getConnection(protocol, prop);

    Statement s = conn.createStatement();
    s.execute("create table t1 (c1 int primary key, c2 char(200))");
    s.execute("insert into t1 (c1, c2) values (10, 'YYYY')");
    s.execute("insert into t1 (c1, c2) values (20, 'YYYY')");
    s.executeQuery("select * from t1 where t1.c1=10");

    conn.close();

    conn = DriverManager.getConnection(protocol, prop);

     s = conn.createStatement();
     s.executeQuery("select * from t1 where t1.c1=10");
    return;
  }

  /**
   * Test multi-connection with db names;
   * 
   * @throws SQLException
   */
  public void testMultiConnectionURLWithName() throws SQLException {

    Connection conn = null;
    Properties props = new Properties();
    props.setProperty("host-data", "true");
    props.setProperty("mcast-port", "0");

    conn = DriverManager.getConnection(protocol, props);

    Statement s = conn.createStatement();
    s.execute("create table t1 (c1 int primary key, c2 char(200))");
    s.execute("insert into t1 (c1, c2) values (10, 'YYYY')");
    s.execute("insert into t1 (c1, c2) values (20, 'YYYY')");
    s.executeQuery("select * from t1 where t1.c1=10");
    conn.close();
    // check exception for dbName using gemfirexd subprotocol
    checkExceptionForURL(protocol + dbName, props);

    // don't fail with dbname "gemfirexd" or upper-case variants
    conn = DriverManager.getConnection(protocol + dbName2, props);
    s = conn.createStatement();
    ResultSet rs = s.executeQuery("select id from sys.members");
    assertTrue(rs.next());
    assertFalse(rs.next());
    conn.close();

    conn = DriverManager.getConnection(protocol + dbName3, props);
    s = conn.createStatement();
    rs = s.executeQuery("select id from sys.members");
    assertTrue(rs.next());
    assertFalse(rs.next());
    conn.close();

    conn = DriverManager.getConnection(protocol + dbName4, props);
    s = conn.createStatement();
    rs = s.executeQuery("select id from sys.members");
    assertTrue(rs.next());
    assertFalse(rs.next());
    conn.close();

    // check exception without dbName using a subsubprotocol
    checkExceptionForURL(protocol + "net:", props);
    // check exception for dbName using a subsubprotocol
    checkExceptionForURL(protocol + "net:" + dbName, props);
    checkExceptionForURL(protocol + "net:" + dbName2, props);
  }

  private void checkExceptionForURL(String url, Properties props)
      throws SQLException {
    Monitor.getStream().println(
        "<ExpectedException action=add>" + "java.sql.SQLException"
            + "</ExpectedException>");
    Monitor.getStream().flush();

    try {
      DriverManager.getConnection(url, props);
      fail("exception is expected!");
    } catch (SQLException ex) {
      if (!"XJ040".equals(ex.getSQLState()) &&
          !"XJ004".equals(ex.getSQLState())) {
        throw ex;
      }
      // pass, expected
    } finally {
      Monitor.getStream().println(
          "<ExpectedException action=remove>" + "java.sql.SQLException"
              + "</ExpectedException>");
      Monitor.getStream().flush();
    }
  }
}
