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

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.net.UnknownHostException;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Properties;

import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.tools.internal.JarTools;

public class GfxdJarInstallationTest extends JdbcTestBase {

  public GfxdJarInstallationTest(String name) {
    super(name);
  }

  private final String myjar = getResourcesDir() + "/lib/myjar.jar";

  private final String myfalsejar = getResourcesDir() + "/lib/myfalsejar.jar";

  private final String booksJar = getResourcesDir() + "/lib/Books.jar";

  public static byte[] getJarBytes(String jar) throws Exception {
    FileInputStream fis = new FileInputStream(jar);
    byte[] data = new byte[4096];
    int len;
    ByteArrayOutputStream bos = new ByteArrayOutputStream();
    while ((len = fis.read(data)) != -1) {
      bos.write(data, 0, len);
    }
    return bos.toByteArray();
  }

  public void testResultProcessorFromInstalledJar() throws Exception {
    Properties props1 = new Properties();
    int mport = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props1.put("mcast-port", String.valueOf(mport));
    setupConnection(props1);
    Connection conn = TestUtil.getConnection(props1);
    Statement s = conn.createStatement();

    s.execute("CREATE TABLE APP.BOOKS(ID VARCHAR(10) NOT NULL, "
        + "NAME VARCHAR(25), TAG VARCHAR(25), SIZE BIGINT, LOCATION VARCHAR(25), "
        + "CONSTRAINT BOOK_PK PRIMARY KEY (ID)) PARTITION BY PRIMARY KEY "
        + "REDUNDANCY 1 EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW PERSISTENT ASYNCHRONOUS");

    s.execute("insert into APP.BOOKS values ('1', 'Spring1','TAG1',20,'Mumbai')");
    s.execute("insert into APP.BOOKS values ('2', 'Spring2','TAG2',10,'Pune')");
    s.execute("insert into APP.BOOKS values ('3', 'Spring3','TAG3',15,'Bangalore')");
    s.execute("insert into APP.BOOKS values ('4', 'Spring4','TAG4',20,'Chennai')");
    s.execute("insert into APP.BOOKS values ('5', 'Spring5','TAG5',10,'calcutta')");
    s.execute("insert into APP.BOOKS values ('6', 'Spring6','TAG6',15,'Bhubaneswar')");
    s.execute("insert into APP.BOOKS values ('7', 'Spring7','TAG7',20,'Delhi')");
    s.execute("insert into APP.BOOKS values ('8', 'Spring8','TAG8',10,'Cuttack')");
    s.execute("insert into APP.BOOKS values ('9', 'Spring9','TAG9',15,'Balangir')");
    s.execute("insert into APP.BOOKS values ('10', 'Spring10','TAG10',20,'Baleswar')");
    s.execute("insert into APP.BOOKS values ('11', 'Spring11','TAG11',10,'Phulbani')");
    s.execute("insert into APP.BOOKS values ('12', 'Spring12','TAG12',15,'Purushoottampur')");
    s.execute("insert into APP.BOOKS values ('13', 'Spring13','TAG13',20,'Puri')");
    s.execute("insert into APP.BOOKS values ('14', 'Spring14','TAG14',10,'Pipili')");
    s.execute("insert into APP.BOOKS values ('15', 'Spring15','TAG15',15,'Keondujhar')");
    s.execute("insert into APP.BOOKS values ('16', 'Spring16','TAG16',20,'Sundargarh')");
    s.execute("insert into APP.BOOKS values ('17', 'Spring17','TAG17',10,'Baripada')");
    s.execute("insert into APP.BOOKS values ('18', 'Spring18','TAG18',15,'Paradip')");
    s.execute("insert into APP.BOOKS values ('19', 'Spring19','TAG19',20,'Parlakhemundi')");
    s.execute("insert into APP.BOOKS values ('20', 'Spring20','TAG20',10,'Gunpur')");
    s.execute("insert into APP.BOOKS values ('21', 'Spring21','TAG21',15,'Aska')");

    s.execute("CALL SQLJ.INSTALL_JAR('" + booksJar
        + "', 'APP.GFXD_LISTBOOKSP', 0);");

    s.execute("CREATE PROCEDURE LIST_BOOKS(IN ID VARCHAR(100) ) "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'com.pivotal.vfabric.booksdb.storedproc.ListBooksStoreProc.execute'");

    CallableStatement stmt = conn
    .prepareCall("{CALL LIST_BOOKS(?)}");
    stmt.setString(1, "10");
    stmt.execute();
    
    stmt = conn
        .prepareCall("{CALL LIST_BOOKS(?) WITH RESULT PROCESSOR com.pivotal.vfabric.booksdb.storedproc.ListBookResultProcessor}");
    stmt.setString(1, "10");

    stmt.execute();

    ResultSet rs = stmt.getResultSet();
    int count = 0;
    while (rs.next()) {
      System.out.println("rs.getString(ID) " + rs.getString(1));
      count++;
    }
    assertEquals(1, count);

    Properties props = new Properties();
    Connection connClient = startNetserverAndGetLocalNetConnection(props);

    CallableStatement stmt_client = connClient
        .prepareCall("{CALL LIST_BOOKS(?)}");
    stmt_client.setString(1, "10");
    stmt_client.execute();
    stmt_client.setString(1, "10");
    stmt_client.execute();
  }
  
  public void testJarInstallThroughVTI() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    String sql = "call SQLJ.INSTALL_JAR_BYTES(?, ?)";
    PreparedStatement ps = conn.prepareStatement(sql);
    ps.setBinaryStream(1, new FileInputStream(myjar));
    ps.setString(2, "app.sample1");
    ps.executeUpdate();

    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)";

    stmt.execute(ddl);

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3')");

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4')");

    stmt.execute("CREATE PROCEDURE MergeSort () "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA "
        + "READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'myexamples.MergeSortProcedure.mergeSort' ");

    stmt.execute("CREATE ALIAS MergeSortProcessor FOR 'myexamples.MergeSortProcessor'");

    sql = "CALL MergeSort() " + "WITH RESULT PROCESSOR MergeSortProcessor "
        + "ON TABLE EMP.PARTITIONTESTTABLE WHERE 1=1";

    CallableStatement cs = conn.prepareCall(sql);
    cs.execute();

    ResultSet rs = cs.getResultSet();

    while (rs.next()) {
      System.out.println(rs.getObject(1));
    }
  }

  public void testBasicJarInstallationFromNetClient() throws Exception {
    //Properties props = new Properties();
    //props.setProperty("log-level", "fine");
    setupConnection();
    int netPort = startNetserverAndReturnPort();
    Connection conn = getNetConnection(netPort, null, null);
    runJarTest(conn, netPort);
    //runPrepTest(conn);
  }

  private void runPrepTest(Connection conn) throws Exception {
    Statement stmt = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)";

    stmt.execute(ddl);
    stmt.execute("insert into EMP.PARTITIONTESTTABLE values "
        + "(1, 1, 'one'), (2, 2, 'two'), (3, 3, 'three')");
    PreparedStatement ps = conn
        .prepareStatement("select * from EMP.PARTITIONTESTTABLE where id = ?");
    ps.setInt(1, 1);
    ps.execute();
    ResultSet rs = ps.getResultSet();
    while (rs.next()) {
      System.out.println(rs.getObject(1) + ", " + rs.getObject(2) + ", "
          + rs.getObject(3));
    }
  }

  public void testBasicJarInstallRemove() throws Exception {
    Connection conn = TestUtil.getConnection();
    runJarTest(conn, -1);
  }

  private void runJarTest(Connection conn, int netPort) throws Exception {
    Statement stmt = conn.createStatement();
    String ddl = "create table EMP.PARTITIONTESTTABLE (ID int NOT NULL,"
        + " SECONDID int not null, THIRDID varchar(10) not null,"
        + " PRIMARY KEY (SECONDID, THIRDID)) PARTITION BY COLUMN (ID)";

    stmt.execute(ddl);

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '3')");
    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (3, 3, '3')");

    stmt.execute("INSERT INTO EMP.PARTITIONTESTTABLE VALUES (2, 2, '4')");

    stmt.execute("call sqlj.install_jar('" + myjar + "', 'app.sample2', 0)");

    stmt.execute("CREATE PROCEDURE MergeSort () "
        + "LANGUAGE JAVA PARAMETER STYLE JAVA "
        + "READS SQL DATA DYNAMIC RESULT SETS 1 "
        + "EXTERNAL NAME 'myexamples.MergeSortProcedure.mergeSort' ");

    stmt.execute("CREATE ALIAS MergeSortProcessor FOR 'myexamples.MergeSortProcessor'");

    final String sql = "CALL MergeSort() "
        + "WITH RESULT PROCESSOR MergeSortProcessor "
        + "ON TABLE EMP.PARTITIONTESTTABLE WHERE 1=1";

    CallableStatement cs = conn.prepareCall(sql);
    cs.execute();

    checkMergeResults(cs);

    stmt.execute("call sqlj.remove_jar('app.sample2', 0)");

    try {
      cs.execute();

      cs.getResultSet();
      fail("expected exception");
    } catch (SQLException ex) {
      if (!"42X51".equals(ex.getSQLState())) {
        throw ex;
      }
    }

    stmt.execute("call sqlj.install_jar('" + myfalsejar
        + "', 'app.sample2', 0)");

    try {
      cs.execute();
      cs.getResultSet();
      fail("fail fail");
    } catch (SQLException ex) {
      assertEquals("42X51", ex.getSQLState());
    }

    stmt.execute("call sqlj.replace_jar('" + myjar + "', 'app.sample2')");
    cs.execute();

    checkMergeResults(cs);

    // now check with the jar tools
    final String localHostName = "localhost";
    if (netPort <= 0) {
      netPort = startNetserverAndReturnPort();
    }
    JarTools.main(new String[] { "remove-jar", "-name=app.sample2",
        "-client-port=" + netPort, "-client-bind-address=" + localHostName });

    try {
      cs.execute();
      cs.getResultSet();
      fail("expected failure with no jar");
    } catch (SQLException sqle) {
      if (!"42X51".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    JarTools.main(new String[] { "install-jar", "-file=" + myjar,
        "-name=app.sample2", "-client-port=" + netPort,
        "-client-bind-address=" + localHostName });
    cs.execute();
    checkMergeResults(cs);

    JarTools.main(new String[] { "remove-jar", "-name=app.sample2",
        "-client-port=" + netPort, "-client-bind-address=" + localHostName });
    JarTools.main(new String[] { "install-jar", "-file=" + myfalsejar,
        "-name=app.sample2", "-client-port=" + netPort,
        "-client-bind-address=" + localHostName });
    try {
      cs.execute();
      cs.getResultSet();
      fail("expected failure with incorrect jar");
    } catch (SQLException sqle) {
      if (!"42X51".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    JarTools.main(new String[] { "replace-jar", "-file=" + myjar,
        "-name=app.sample2", "-client-port=" + netPort,
        "-client-bind-address=" + localHostName });
    cs.execute();
    checkMergeResults(cs);
  }

  private void checkMergeResults(CallableStatement cs) throws SQLException {
    ResultSet rs = cs.getResultSet();
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(2, rs.getInt(1));
    assertEquals(2, rs.getInt(2));
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    assertEquals(3, rs.getInt(2));
    assertFalse(rs.next());
  }

  public void testUDTINJarFromPeerClient() throws Exception {
    Connection conn = TestUtil.getConnection();
    udtTypeTest(conn, false, false, true, true, false);
    udtTypeTest(conn, true, false, true, true, false);
  }

  public void testUDTINJarFromNetClient() throws Exception {
    setupConnection();
    Connection conn = startNetserverAndGetLocalNetConnection();
    udtTypeTest(conn, true, false, true, true, false);
    udtTypeTest(conn, false, false, true, true, false);
  }

  public static final String pricejar = getResourcesDir() + "/lib/myPrice.jar";

  @SuppressWarnings("unchecked")
  public static void udtTypeTest(Connection conn, boolean vti,
      boolean expectClassLoadEx, boolean createDBObjects,
      boolean dropDBObjects, boolean persistent) throws Exception {
    Statement stmt = conn.createStatement();
    PreparedStatement ps;
    if (createDBObjects) {
      if (!vti) {
        stmt.execute("call sqlj.install_jar('" + pricejar
            + "','APP.udtjar', 0)");
      }
      else {
        byte[] jarBytes = getJarBytes(pricejar);
        String sql = "call sqlj.install_jar_bytes(?, ?)";
        ps = conn.prepareStatement(sql);
        ps.setBytes(1, jarBytes);
        ps.setString(2, "APP.udtjar");
        ps.executeUpdate();
      }

      createUDTDBObjects(stmt, persistent);

      // inserts
      ps = conn
          .prepareStatement("insert into orders values (?, ?, makePrice('INR', "
              + "?, timestamp('2009-10-16 14:24:43')))");
      for (int id = 1; id <= 10; id++) {
        ps.setInt(1, id);
        ps.setInt(2, id + 10);
        ps.setBigDecimal(3, new BigDecimal(Integer.toString(id) + ".90000"));
        ps.execute();
      }
    }

    // now selects to check the data back
    ResultSet rs = stmt.executeQuery("select orderID, customerID, "
        + "getCurrencyCode(totalPrice), getAmount(totalPrice) "
        + "from orders order by orderID");
    int numResults = 0;
    while (rs.next()) {
      numResults++;
      int id = numResults;
      assertEquals(id, rs.getInt(1));
      assertEquals(id + 10, rs.getInt(2));
      assertEquals("INR", rs.getString(3));
      assertEquals(new BigDecimal(Integer.toString(id) + ".90000"),
          rs.getBigDecimal(4));
    }
    assertEquals(10, numResults);

    // fire some updates and try again
    ps = conn.prepareStatement("update orders set customerID = ? "
        + "where orderID = ?");
    for (int id = 5; id <= 10; id++) {
      ps.setInt(1, id + 20);
      ps.setInt(2, id);
      assertEquals(1, ps.executeUpdate());
    }
    // now selects to check the data back
    rs = stmt.executeQuery("select orderID, customerID, "
        + "getCurrencyCode(totalPrice), getAmount(totalPrice) "
        + "from orders order by orderID");
    numResults = 0;
    while (rs.next()) {
      numResults++;
      int id = numResults;
      assertEquals(id, rs.getInt(1));
      if (id < 5) {
        assertEquals(id + 10, rs.getInt(2));
      }
      else {
        assertEquals(id + 20, rs.getInt(2));
      }
      assertEquals("INR", rs.getString(3));
      assertEquals(new BigDecimal(Integer.toString(id) + ".90000"),
          rs.getBigDecimal(4));
    }
    assertEquals(10, numResults);

    // fire some updates on price and try again
    ps = conn.prepareStatement("update orders set totalPrice = makePrice('INR'"
        + ", ?, timestamp('2010-10-16 14:24:43')) where orderID = ?");
    for (int id = 1; id <= 5; id++) {
      ps.setBigDecimal(1, new BigDecimal(Integer.toString(id * 2) + ".88000"));
      ps.setInt(2, id);
      assertEquals(1, ps.executeUpdate());
    }
    // now selects to check the data back
    rs = stmt.executeQuery("select orderID, customerID, "
        + "getCurrencyCode(totalPrice), getAmount(totalPrice) "
        + "from orders order by orderID");
    numResults = 0;
    while (rs.next()) {
      numResults++;
      int id = numResults;
      assertEquals(id, rs.getInt(1));
      if (id < 5) {
        assertEquals(id + 10, rs.getInt(2));
      }
      else {
        assertEquals(id + 20, rs.getInt(2));
      }
      assertEquals("INR", rs.getString(3));
      if (id <= 5) {
        assertEquals(new BigDecimal(Integer.toString(id * 2) + ".88000"),
            rs.getBigDecimal(4));
      }
      else {
        assertEquals(new BigDecimal(Integer.toString(id) + ".90000"),
            rs.getBigDecimal(4));
      }
    }
    assertEquals(10, numResults);

    ps = conn.prepareStatement("delete from orders where orderID = ?");
    ps.setInt(1, 4);
    assertEquals(1, ps.executeUpdate());
    // now selects to check the data back
    rs = stmt.executeQuery("select orderID, customerID, "
        + "getCurrencyCode(totalPrice), getAmount(totalPrice) "
        + "from orders order by orderID");
    numResults = 0;
    while (rs.next()) {
      numResults++;
      if (numResults == 4) {
        numResults++;
      }
      int id = numResults;
      assertEquals(id, rs.getInt(1));
      if (id < 5) {
        assertEquals(id + 10, rs.getInt(2));
      }
      else {
        assertEquals(id + 20, rs.getInt(2));
      }
      assertEquals("INR", rs.getString(3));
      if (id <= 5) {
        assertEquals(new BigDecimal(Integer.toString(id * 2) + ".88000"),
            rs.getBigDecimal(4));
      }
      else {
        assertEquals(new BigDecimal(Integer.toString(id) + ".90000"),
            rs.getBigDecimal(4));
      }
    }
    assertEquals(10, numResults);

    // restore data back to original for cases of persistence testing
    if (!dropDBObjects) {
      stmt.execute("delete from orders");
      ps = conn
          .prepareStatement("insert into orders values (?, ?, makePrice('INR', "
              + "?, timestamp('2009-10-16 14:24:43')))");
      for (int id = 1; id <= 10; id++) {
        ps.setInt(1, id);
        ps.setInt(2, id + 10);
        ps.setBigDecimal(3, new BigDecimal(Integer.toString(id) + ".90000"));
        ps.execute();
      }
    }

    // try hard to ensure that old ClassLoaders are GCed
    if (expectClassLoadEx) {
      ClientSharedUtils.ALLOW_THREADCONTEXT_CLASSLOADER.set(Boolean.TRUE);
      System.gc();
      System.runFinalization();
    }

    // expect class loading exception when trying to read Price object itself
    rs = stmt.executeQuery("select * from orders where orderID = 1");
    assertTrue(rs.next());
    try {
      getLogger().info(String.valueOf(rs.getObject(3)));
      if (expectClassLoadEx) {
        // try bit harder to ensure that old ClassLoaders are GCed
        System.gc();
        System.runFinalization();
        Thread.sleep(3000);
        System.gc();
        System.runFinalization();
        Thread.sleep(3000);

        getLogger().info(String.valueOf(rs.getObject(3)));
        fail("expected class load exception");
      }
    } catch (SQLException sqle) {
      Throwable t = sqle;
      while (t.getCause() != null) {
        t = t.getCause();
      }
      assertTrue("unexpected exception " + t,
          t instanceof ClassNotFoundException);
      assertTrue("unexpected exception " + t,
          t.getMessage().contains("udtexamples.Price"));
    }
    assertFalse(rs.next());

    // expect exception with dependent table
    try {
      stmt.execute("drop type Price restrict");
      fail("expect exception in type removal due to dependent table");
    } catch (SQLException sqle) {
      if (!"X0Y30".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    if (dropDBObjects) {
      dropUDTDBObjects(stmt);
      stmt.execute("call sqlj.remove_jar('APP.udtjar', 0)");
    }

    if (!expectClassLoadEx) {
      return;
    }

    // now try after loading the class in this VM too
    if (createDBObjects) {
      if (!vti) {
        stmt.execute("call sqlj.install_jar('" + pricejar
            + "','APP.udtjar', 0)");
      }
      else {
        String sql = "call sqlj.install_jar_bytes(?, ?)";
        CallableStatement cs = conn.prepareCall(sql);
        cs.setBinaryStream(1, new FileInputStream(pricejar));
        cs.setString(2, "APP.udtjar");
        cs.executeUpdate();
      }

      createUDTDBObjects(stmt, persistent);
    }

    ClassLoader origCL = Thread.currentThread().getContextClassLoader();
    ClassLoader cl = new URLClassLoader(
        new URL[] { new URL(pricejar.charAt(0) == '/' ? "file:" + pricejar
            : "file:/" + pricejar) }, origCL);
    Thread.currentThread().setContextClassLoader(cl);

    Class<?> priceClazz = cl.loadClass("udtexamples.Price");
    if (createDBObjects) {
      // inserts
      ps = conn.prepareStatement("insert into orders values (?, ?, ?)");
      for (int id = 1; id <= 10; id++) {
        ps.setInt(1, id);
        ps.setInt(2, id + 10);
        Object p = priceClazz.getMethod("makePrice",
            new Class[] { String.class, BigDecimal.class, Timestamp.class })
            .invoke(null, "INR",
                new BigDecimal(Integer.toString(id) + ".90000"),
                Timestamp.valueOf("2009-10-16 14:24:43"));
        ps.setObject(3, p);
        ps.execute();
      }
    }

    // now selects to check the data back
    rs = stmt.executeQuery("select * from orders order by orderID");
    numResults = 0;
    while (rs.next()) {
      numResults++;
      int id = numResults;
      assertEquals(id, rs.getInt(1));
      assertEquals(id + 10, rs.getInt(2));
      Object p = priceClazz.getMethod("makePrice",
          new Class[] { String.class, BigDecimal.class, Timestamp.class })
          .invoke(null, "INR", new BigDecimal(Integer.toString(id) + ".90000"),
              Timestamp.valueOf("2009-10-16 14:24:43"));
      assertEquals(p, rs.getObject(3));
    }
    assertEquals(10, numResults);

    // fire some updates and try again
    ps = conn.prepareStatement("update orders set customerID = ? "
        + "where orderID = ?");
    for (int id = 5; id <= 10; id++) {
      ps.setInt(1, id + 20);
      ps.setInt(2, id);
      assertEquals(1, ps.executeUpdate());
    }
    // now selects to check the data back
    rs = stmt.executeQuery("select * from orders order by orderID");
    numResults = 0;
    while (rs.next()) {
      numResults++;
      int id = numResults;
      assertEquals(id, rs.getInt(1));
      if (id < 5) {
        assertEquals(id + 10, rs.getInt(2));
      }
      else {
        assertEquals(id + 20, rs.getInt(2));
      }
      Object p = priceClazz.getMethod("makePrice",
          new Class[] { String.class, BigDecimal.class, Timestamp.class })
          .invoke(null, "INR", new BigDecimal(Integer.toString(id) + ".90000"),
              Timestamp.valueOf("2009-10-16 14:24:43"));
      assertEquals(p, rs.getObject(3));
    }
    assertEquals(10, numResults);

    // fire some updates on price and try again
    ps = conn.prepareStatement("update orders set totalPrice = ? "
        + "where orderID = ?");
    for (int id = 1; id <= 5; id++) {
      Object p = priceClazz.getMethod("makePrice",
          new Class[] { String.class, BigDecimal.class, Timestamp.class })
          .invoke(null, "INR",
              new BigDecimal(Integer.toString(id * 2) + ".88000"),
              Timestamp.valueOf("2010-10-16 14:24:43"));
      ps.setObject(1, p);
      ps.setInt(2, id);
      assertEquals(1, ps.executeUpdate());
    }
    // now selects to check the data back
    rs = stmt.executeQuery("select * from orders order by orderID");
    numResults = 0;
    while (rs.next()) {
      numResults++;
      int id = numResults;
      assertEquals(id, rs.getInt(1));
      if (id < 5) {
        assertEquals(id + 10, rs.getInt(2));
      }
      else {
        assertEquals(id + 20, rs.getInt(2));
      }
      if (id <= 5) {
        Object p = priceClazz.getMethod("makePrice",
            new Class[] { String.class, BigDecimal.class, Timestamp.class })
            .invoke(null, "INR",
                new BigDecimal(Integer.toString(id * 2) + ".88000"),
                Timestamp.valueOf("2010-10-16 14:24:43"));
        assertEquals(p, rs.getObject(3));
      }
      else {
        Object p = priceClazz.getMethod("makePrice",
            new Class[] { String.class, BigDecimal.class, Timestamp.class })
            .invoke(null, "INR",
                new BigDecimal(Integer.toString(id) + ".90000"),
                Timestamp.valueOf("2009-10-16 14:24:43"));
        assertEquals(p, rs.getObject(3));
      }
    }
    assertEquals(10, numResults);

    ps = conn.prepareStatement("delete from orders where orderID = ?");
    ps.setInt(1, 4);
    assertEquals(1, ps.executeUpdate());
    // now selects to check the data back
    rs = stmt.executeQuery("select * from orders order by orderID");
    numResults = 0;
    while (rs.next()) {
      numResults++;
      if (numResults == 4) {
        numResults++;
      }
      int id = numResults;
      assertEquals(id, rs.getInt(1));
      if (id < 5) {
        assertEquals(id + 10, rs.getInt(2));
      }
      else {
        assertEquals(id + 20, rs.getInt(2));
      }
      if (id <= 5) {
        Object p = priceClazz.getMethod("makePrice",
            new Class[] { String.class, BigDecimal.class, Timestamp.class })
            .invoke(null, "INR",
                new BigDecimal(Integer.toString(id * 2) + ".88000"),
                Timestamp.valueOf("2010-10-16 14:24:43"));
        assertEquals(p, rs.getObject(3));
      }
      else {
        Object p = priceClazz.getMethod("makePrice",
            new Class[] { String.class, BigDecimal.class, Timestamp.class })
            .invoke(null, "INR",
                new BigDecimal(Integer.toString(id) + ".90000"),
                Timestamp.valueOf("2009-10-16 14:24:43"));
        assertEquals(p, rs.getObject(3));
      }
    }
    assertEquals(10, numResults);

    // restore data back to original for cases of persistence testing
    if (!dropDBObjects) {
      stmt.execute("delete from orders");
      ps = conn.prepareStatement("insert into orders values (?, ?, ?)");
      for (int id = 1; id <= 10; id++) {
        ps.setInt(1, id);
        ps.setInt(2, id + 10);
        Object p = priceClazz.getMethod("makePrice",
            new Class[] { String.class, BigDecimal.class, Timestamp.class })
            .invoke(null, "INR",
                new BigDecimal(Integer.toString(id) + ".90000"),
                Timestamp.valueOf("2009-10-16 14:24:43"));
        ps.setObject(3, p);
        ps.execute();
      }
    }

    // expect exception with dependent table
    try {
      stmt.execute("drop type Price restrict");
      fail("expect exception in type removal due to dependent table");
    } catch (SQLException sqle) {
      if (!"X0Y30".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    Thread.currentThread().setContextClassLoader(origCL);

    if (dropDBObjects) {
      dropUDTDBObjects(stmt);
      stmt.execute("call sqlj.remove_jar('APP.udtjar', 0)");
    }
  }

  public static void createUDTDBObjects(Statement stmt, boolean persistent)
      throws SQLException {
    stmt.execute("create type Price external name 'udtexamples.Price' "
        + "language java");
    stmt.execute("create table orders( orderID int, customerID int, "
        + "totalPrice price)" + (persistent ? " PERSISTENT" : ""));
    stmt.execute("create function makePrice( currencyCode char( 3 ), "
        + "amount decimal( 31, 5 ), timeInstant Timestamp ) "
        + "returns Price language java parameter style java no sql "
        + "external name 'udtexamples.Price.makePrice'");
    stmt.execute("create function getCurrencyCode( price Price ) "
        + "returns char( 3 ) language java parameter style java no sql "
        + "external name 'udtexamples.Price.getCurrencyCode'");
    stmt.execute("create function getAmount( price Price ) returns "
        + "decimal( 31, 5 ) language java parameter style java no sql "
        + "external name 'udtexamples.Price.getAmount'");
    stmt.execute("create function getTimeInstant( price Price ) returns "
        + "timestamp language java parameter style java no sql "
        + "external name 'udtexamples.Price.getTimeInstant'");
    stmt.execute("create procedure savePrice( in a Price ) language java "
        + "parameter style java no sql "
        + "external name 'udtexamples.Price.savePrice'");
    stmt.execute("create function getSavedPrice() returns Price language java "
        + "parameter style java no sql "
        + "external name 'udtexamples.Price.getSavedPrice'");
  }

  public static void dropUDTDBObjects(Statement stmt) throws SQLException {
    stmt.execute("drop table orders");
    stmt.execute("drop function makePrice");
    stmt.execute("drop function getCurrencyCode");
    stmt.execute("drop function getAmount");
    stmt.execute("drop function getTimeInstant");
    stmt.execute("drop procedure savePrice");
    stmt.execute("drop function getSavedPrice");
    stmt.execute("drop type Price restrict");
  }
  
  public void testBug50285() throws SQLException, UnknownHostException {
    Properties props = new Properties();
    {
      Connection conn = TestUtil.getConnection(props);
      Statement st = conn.createStatement();
      st.execute("create type Person external name 'com.pivotal.gemfirexd.jdbc.Person' "
          + "language java");
      st.execute("CREATE FUNCTION create_person(VARCHAR(32)) RETURNS Person   "
          + "LANGUAGE JAVA  "
          + "PARAMETER STYLE JAVA  "
          + "NO SQL  "
          + "EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.CreatePerson.newInstance'");
      st.execute("CREATE FUNCTION update_person(Person, VARCHAR(32)) RETURNS Person   "
          + "LANGUAGE JAVA  "
          + "PARAMETER STYLE JAVA  "
          + "NO SQL  "
          + "EXTERNAL NAME 'com.pivotal.gemfirexd.jdbc.CreatePerson.updateInstance'");
      // Create table
      st.execute("CREATE TABLE exampleTable ( " 
          + "DUMMY_ID INT NOT NULL, "
          + "REAL_ID INT NOT NULL, " 
          + "Person Person, "
          + "PRIMARY KEY( DUMMY_ID ) " 
          + ") PARTITION BY COLUMN (DUMMY_ID)"
      );
      st.execute("insert into exampleTable values (1, 1, create_person('one'))");
      st.execute("insert into exampleTable values (2, 2, create_person('two'))");
    }

    {
      Connection conn = TestUtil.getConnection(props);
      String query = "update exampleTable set Person = update_person(Person, ?) where DUMMY_ID = ?";
      PreparedStatement st = conn.prepareStatement(query);
      st.setString(1, "four");
      st.setInt(2, 2);
      int r = st.executeUpdate();
      assertEquals(1, r);
    }

    {
      Connection conn = TestUtil.getConnection(props);
      String query = "update exampleTable set REAL_ID = ?, Person = update_person(Person, ?) where DUMMY_ID = ?";
      System.out.println(query);
      PreparedStatement st = conn.prepareStatement(query);
      st.setInt(1, 3);
      st.setString(2, "three");
      st.setInt(3, 1);
      int r = st.executeUpdate();
      assertEquals(1, r);
    }
    
    
    {
      Connection conn = TestUtil.getConnection(props);
      String query = "Select REAL_ID, person, DUMMY_ID from exampleTable where DUMMY_ID = ?";
      PreparedStatement st = conn.prepareStatement(query);
      st.setInt(1, 2);
      ResultSet r = st.executeQuery();
      int count = 0;
      while (r.next()) {
        assertEquals("four", r.getObject(2).toString().toLowerCase());
        count++;
      }
      assertEquals(1, count);
      r.close();
    }
    
    
    {
      Connection conn = TestUtil.getConnection(props);
      String query = "Select REAL_ID, person, DUMMY_ID from exampleTable where DUMMY_ID = ?";
      PreparedStatement st = conn.prepareStatement(query);
      st.setInt(1, 1);
      ResultSet r = st.executeQuery();
      int count = 0;
      while (r.next()) {
        assertEquals("three", r.getObject(2).toString().toLowerCase());
        count++;
      }
      assertEquals(1, count);
      r.close();
    }
  }
}
