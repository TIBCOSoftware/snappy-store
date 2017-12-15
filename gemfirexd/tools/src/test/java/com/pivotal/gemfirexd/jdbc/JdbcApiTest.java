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

import java.sql.*;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

public class JdbcApiTest extends JdbcTestBase {

  private static final int[] ALL_ISOLATION_LEVELS = new int[] {
      Connection.TRANSACTION_NONE,
      Connection.TRANSACTION_READ_UNCOMMITTED,
      Connection.TRANSACTION_READ_COMMITTED,
      Connection.TRANSACTION_REPEATABLE_READ,
      Connection.TRANSACTION_SERIALIZABLE };

  public JdbcApiTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(JdbcApiTest.class));
  }

  public void testConnectionGetTransactionIsolation() throws SQLException {
    Connection conn = getConnection();
    try {
      // only one isolation level is currently supported
      if(isTransactional)
        assertEquals(Connection.TRANSACTION_READ_COMMITTED, conn.getTransactionIsolation());
      else
        assertEquals(Connection.TRANSACTION_NONE, conn.getTransactionIsolation());
    } finally {
      conn.close();
    }
  }

  public void testConnectionSetTransactionIsolation() throws SQLException {
    Connection conn = getConnection();
    try {
      for (int txnLvl : ALL_ISOLATION_LEVELS) {
        try {
          conn.setTransactionIsolation(txnLvl);
          // setting txn level to TRANSACTION_NONE is allowed since it
          // is a no-op
          if (txnLvl == Connection.TRANSACTION_SERIALIZABLE) {
            fail("Should have thrown an SQLException with SQLState class XJ045");
          }
        } catch (SQLException sqle) {
          if (txnLvl == Connection.TRANSACTION_NONE) {
            throw sqle;
          }
          String sqlState = sqle.getSQLState();
          assertTrue("got sqlState=" + sqlState
              + " when setting isolation level to " + txnLvl,
              sqlState.startsWith("XJ045")); // Feature Not Supported
        }
      }
    } finally {
      conn.close();
    }
  }

  public void testDatabaseMetadataTransactions() throws SQLException {
    Connection conn = getConnection();
    try {
      DatabaseMetaData dbmd = conn.getMetaData();
      assertEquals(Connection.TRANSACTION_NONE,
          dbmd.getDefaultTransactionIsolation());
      // assertFalse(dbmd.supportsMultipleTransactions());
      assertTrue(dbmd.supportsMultipleTransactions());
      // assertFalse(dbmd.supportsTransactions());
      assertTrue(dbmd.supportsTransactions());

      int txnLvl;

      txnLvl = Connection.TRANSACTION_NONE;
      assertTrue(dbmd.supportsTransactionIsolationLevel(txnLvl));

      txnLvl = Connection.TRANSACTION_READ_COMMITTED;
      assertTrue(dbmd.supportsTransactionIsolationLevel(txnLvl));

      txnLvl = Connection.TRANSACTION_READ_UNCOMMITTED;
      assertTrue(dbmd.supportsTransactionIsolationLevel(txnLvl));

      txnLvl = Connection.TRANSACTION_REPEATABLE_READ;
      assertTrue(dbmd.supportsTransactionIsolationLevel(txnLvl));

      txnLvl = Connection.TRANSACTION_SERIALIZABLE;
      assertFalse(dbmd.supportsTransactionIsolationLevel(txnLvl));
    } finally {
      conn.close();
    }
  }

  /**
   * Test of the metadata and results returned by Statement.getGeneratedKeys,
   * see bug 43967
   * 
   * @throws SQLException
   */
  public void testGetGeneratedKeys() throws SQLException {
    Connection conn = getConnection();

    try {
      Statement s = conn.createStatement();
      s.execute("CREATE TABLE owners("
          + "ID INTEGER NOT NULL GENERATED ALWAYS AS IDENTITY, "
          + "first_name VARCHAR(30), "
          + "last_name VARCHAR(30), "
          + "address VARCHAR(255), "
          + "city VARCHAR(80), "
          + "telephone VARCHAR(20), "
          + "PRIMARY KEY (id))");

      PreparedStatement ps = conn.prepareStatement("INSERT INTO owners "
          + "(FIRST_NAME, LAST_NAME, ADDRESS, CITY, TELEPHONE) "
          + "VALUES(?, ?, ?, ?, ?)", Statement.RETURN_GENERATED_KEYS);
      ps.setString(1, "joe");
      ps.setString(2, "joe");
      ps.setString(3, "joe");
      ps.setString(4, "joe");
      ps.setString(5, "joe");
      ps.execute();
      ResultSet rs = ps.getGeneratedKeys();
      rs.next();
      assertEquals(1, rs.getMetaData().getColumnCount());
      // assertEquals("ID", rs.getMetaData().getColumnName(1));

      // This should succeed, but we have no assertion about what
      // the actual integer should be.
      assertEquals("ID", rs.getMetaData().getColumnName(1));
      assertEquals(Integer.class, rs.getObject(1).getClass());
      assertFalse(rs.next());
    } finally {
      conn.close();
    }
  }

  /**
   * Allow for qualified names in column names for ResultSet API (#41272)
   */
  public void testRSQualifiedNames_41272() throws Exception {
    Connection conn = getConnection();
    Connection netConn = startNetserverAndGetLocalNetConnection();

    Statement s = conn.createStatement();
    s.execute("CREATE TABLE o.owner("
        + "ID INTEGER NOT NULL , "
        + "address VARCHAR(255), "
        + "city VARCHAR(80), "
        + "telephone VARCHAR(20), "
        + "PRIMARY KEY (id)) replicate");
    s.execute("CREATE TABLE o.ownername("
        + "ID INTEGER NOT NULL , "
        + "firstname VARCHAR(30), "
        + "lastname VARCHAR(30), "
        + "city VARCHAR(80), "
        + "PRIMARY KEY (id)) replicate");

    PreparedStatement ps = conn.prepareStatement("INSERT INTO o.owner "
        + "(ID, ADDRESS, CITY, TELEPHONE) VALUES(?, ?, ?, ?)");
    for (int id = 1; id <= 10; id++) {
      ps.setInt(1, id);
      ps.setString(2, "addr" + id);
      ps.setString(3, "city" + id);
      ps.setString(4, "tel" + id);
      ps.execute();
    }

    ps = conn.prepareStatement("INSERT INTO o.ownername VALUES(?, ?, ?, ?)");
    for (int id = 1; id <= 10; id++) {
      ps.setInt(1, id);
      ps.setString(2, "fname" + id);
      ps.setString(3, "sname" + id);
      ps.setString(4, "city_" + id);
      ps.execute();
    }

    verifyResults(conn);
    verifyResults(netConn);
  }

  // check the meta-data of meta-data queries
  public void testMetadataOfMetadata_SNAP2083() throws Exception {
    // check for embedded connection
    Connection conn = getConnection();
    checkMetadataOfMetadata(conn);
    conn.close();

    // check for thin connection
    final int netPort = startNetserverAndReturnPort();
    // use this VM as network client and also peer client
    final Connection netConn = getNetConnection(netPort, null, null);
    checkMetadataOfMetadata(netConn);
    netConn.close();

    stopNetServer();
  }

  private void verifyResults(Connection conn) throws SQLException {
    Statement stmt = conn.createStatement();
    ResultSet rs = stmt.executeQuery("select * from o.owner o, o.ownername n "
        + "where o.id = n.id order by o.id");
    for (int id = 1; id <= 10; id++) {
      assertTrue(rs.next());

      assertEquals(id, rs.getInt("ID"));
      assertEquals(id, rs.getInt("id"));
      assertEquals(id, rs.getObject("ID"));
      assertEquals(id, rs.getObject("id"));

      // expect first column to be selected as per JDBC spec
      assertEquals("city" + id, rs.getString("CITY"));
      assertEquals("city" + id, rs.getString("City"));
      assertEquals("city" + id, rs.getObject("CITY"));
      assertEquals("city" + id, rs.getObject("City"));

      // check GFXD extension of qualified name
      assertEquals("city" + id, rs.getString("O.OWNER.CITY"));
      assertEquals("city" + id, rs.getString("Owner.City"));
      assertEquals("city_" + id, rs.getString("O.OWNERNAME.CITY"));
      assertEquals("city_" + id, rs.getString("ownername.City"));
      assertEquals("city" + id, rs.getObject("OWNER.CITY"));
      assertEquals("city" + id, rs.getObject("o.owner.City"));
      assertEquals("city_" + id, rs.getObject("OWNERNAME.CITY"));
      assertEquals("city_" + id, rs.getObject("o.OwnerName.City"));

      assertEquals(id, rs.getInt("O.OWNER.ID"));
      assertEquals(id, rs.getInt("Owner.id"));
      assertEquals(id, rs.getInt("OWNERNAME.ID"));
      assertEquals(id, rs.getInt("o.ownerName.Id"));
      assertEquals(id, rs.getObject("OWNER.ID"));
      assertEquals(id, rs.getObject("O.owner.id"));
      assertEquals(id, rs.getObject("O.OWNERNAME.ID"));
      assertEquals(id, rs.getObject("ownername.id"));

      assertEquals("addr" + id, rs.getString("ADDRESS"));
      assertEquals("addr" + id, rs.getString("address"));
      assertEquals("addr" + id, rs.getString("OWNER.ADDRESS"));
      assertEquals("addr" + id, rs.getString("o.owner.Address"));
      assertEquals("addr" + id, rs.getObject("ADDRESS"));
      assertEquals("addr" + id, rs.getObject("address"));
      assertEquals("addr" + id, rs.getObject("O.OWNER.ADDRESS"));
      assertEquals("addr" + id, rs.getObject("Owner.address"));

      assertEquals("tel" + id, rs.getString("TELEPHONE"));
      assertEquals("tel" + id, rs.getString("Telephone"));
      assertEquals("tel" + id, rs.getString("O.OWNER.TELEPHONE"));
      assertEquals("tel" + id, rs.getString("OWNER.telephone"));
      assertEquals("tel" + id, rs.getObject("TELEPHONE"));
      assertEquals("tel" + id, rs.getObject("Telephone"));
      assertEquals("tel" + id, rs.getObject("OWNER.TELEPHONE"));
      assertEquals("tel" + id, rs.getObject("o.owner.telephone"));

      assertEquals("fname" + id, rs.getString("FIRSTNAME"));
      assertEquals("fname" + id, rs.getString("FirstName"));
      assertEquals("fname" + id, rs.getString("O.OWNERNAME.FIRSTNAME"));
      assertEquals("fname" + id, rs.getString("OwnerName.firstName"));
      assertEquals("fname" + id, rs.getObject("FIRSTNAME"));
      assertEquals("fname" + id, rs.getObject("firstname"));
      assertEquals("fname" + id, rs.getObject("OWNERNAME.FIRSTNAME"));
      assertEquals("fname" + id, rs.getObject("O.ownerName.firstname"));

      assertEquals("sname" + id, rs.getString("LASTNAME"));
      assertEquals("sname" + id, rs.getString("LastName"));
      assertEquals("sname" + id, rs.getString("O.OWNERNAME.LASTNAME"));
      assertEquals("sname" + id, rs.getString("o.ownerName.lastName"));
      assertEquals("sname" + id, rs.getObject("LASTNAME"));
      assertEquals("sname" + id, rs.getObject("lastname"));
      assertEquals("sname" + id, rs.getObject("OWNERNAME.LASTNAME"));
      assertEquals("sname" + id, rs.getObject("ownername.lastname"));
    }

    rs = stmt.executeQuery("select o.id, n.id, o.city, n.city, o.address "
        + "from o.owner o, o.ownername n " + "where o.id = n.id order by o.id");
    for (int id = 1; id <= 10; id++) {
      assertTrue(rs.next());

      assertEquals(id, rs.getInt("ID"));
      assertEquals(id, rs.getInt("id"));
      assertEquals(id, rs.getObject("ID"));
      assertEquals(id, rs.getObject("id"));

      // expect first column to be selected as per JDBC spec
      assertEquals("city" + id, rs.getString("CITY"));
      assertEquals("city" + id, rs.getString("City"));
      assertEquals("city" + id, rs.getObject("CITY"));
      assertEquals("city" + id, rs.getObject("City"));

      // check GFXD extension of qualified name
      assertEquals("city" + id, rs.getString("O.OWNER.CITY"));
      assertEquals("city" + id, rs.getString("Owner.City"));
      assertEquals("city_" + id, rs.getString("O.OWNERNAME.CITY"));
      assertEquals("city_" + id, rs.getString("ownername.City"));
      assertEquals("city" + id, rs.getObject("OWNER.CITY"));
      assertEquals("city" + id, rs.getObject("o.owner.City"));
      assertEquals("city_" + id, rs.getObject("OWNERNAME.CITY"));
      assertEquals("city_" + id, rs.getObject("o.OwnerName.City"));

      assertEquals(id, rs.getInt("O.OWNER.ID"));
      assertEquals(id, rs.getInt("Owner.id"));
      assertEquals(id, rs.getInt("OWNERNAME.ID"));
      assertEquals(id, rs.getInt("o.ownerName.Id"));
      assertEquals(id, rs.getObject("OWNER.ID"));
      assertEquals(id, rs.getObject("O.owner.id"));
      assertEquals(id, rs.getObject("O.OWNERNAME.ID"));
      assertEquals(id, rs.getObject("ownername.id"));

      assertEquals("addr" + id, rs.getString("ADDRESS"));
      assertEquals("addr" + id, rs.getString("address"));
      assertEquals("addr" + id, rs.getString("OWNER.ADDRESS"));
      assertEquals("addr" + id, rs.getString("o.owner.Address"));
      assertEquals("addr" + id, rs.getObject("ADDRESS"));
      assertEquals("addr" + id, rs.getObject("address"));
      assertEquals("addr" + id, rs.getObject("O.OWNER.ADDRESS"));
      assertEquals("addr" + id, rs.getObject("Owner.address"));
    }
  }

  private void checkMetadataOfMetadata(Connection conn) throws SQLException {
    ResultSet rs = conn.getMetaData().getColumns("", "APP", "", "%");
    ResultSetMetaData rsmd = rs.getMetaData();

    // check that NULLABLE column should be of type INTEGER
    assertEquals("NULLABLE", rsmd.getColumnName(11));
    assertEquals(Types.INTEGER, rsmd.getColumnType(11));

    // check the width of TABLE_CAT, TABLE_SCHEM, TABLE_NAME, COLUMN_NAME, TYPE_NAME
    assertEquals("TABLE_CAT", rsmd.getColumnName(1));
    assertEquals(Types.VARCHAR, rsmd.getColumnType(1));
    assertEquals(128, rsmd.getPrecision(1));
    assertEquals("TABLE_SCHEM", rsmd.getColumnName(2));
    assertEquals(Types.VARCHAR, rsmd.getColumnType(2));
    assertEquals(128, rsmd.getPrecision(2));
    assertEquals("TABLE_NAME", rsmd.getColumnName(3));
    assertEquals(Types.VARCHAR, rsmd.getColumnType(3));
    assertEquals(128, rsmd.getPrecision(3));
    assertEquals("COLUMN_NAME", rsmd.getColumnName(4));
    assertEquals(Types.VARCHAR, rsmd.getColumnType(4));
    assertEquals(128, rsmd.getPrecision(4));
    assertEquals("TYPE_NAME", rsmd.getColumnName(6));
    assertEquals(Types.VARCHAR, rsmd.getColumnType(6));
    assertEquals(128, rsmd.getPrecision(6));

    rs.close();
  }
}
