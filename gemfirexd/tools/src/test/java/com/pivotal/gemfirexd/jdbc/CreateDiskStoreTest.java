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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import org.apache.derbyTesting.junit.JDBC;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.DiskStore;
import com.gemstone.gemfire.cache.DiskStoreFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;

/**
 * 
 * @author ashahid
 * 
 */
public class CreateDiskStoreTest extends JdbcTestBase
{

  public static void main(String[] args)
  {
    TestRunner.run(new TestSuite(SimpleAppTest.class));
  }

  public CreateDiskStoreTest(String name) {
    super(name);
  }

  public void testDiskStoreWithDefaultConfig() throws SQLException
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    s.execute("create DiskStore testDiskStore1");
    DiskStore ds = Misc.getGemFireCache().findDiskStore("TESTDISKSTORE1");

    assertEquals(ds.getAllowForceCompaction(),
        DiskStoreFactory.DEFAULT_ALLOW_FORCE_COMPACTION);
    assertEquals(ds.getAutoCompact(), DiskStoreFactory.DEFAULT_AUTO_COMPACT);
    assertEquals(ds.getCompactionThreshold(),
        DiskStoreFactory.DEFAULT_COMPACTION_THRESHOLD);
    assertEquals(ds.getMaxOplogSize(), DiskStoreFactory.DEFAULT_MAX_OPLOG_SIZE);
    assertEquals(ds.getQueueSize(), DiskStoreFactory.DEFAULT_QUEUE_SIZE);
    assertEquals(ds.getTimeInterval(), DiskStoreFactory.DEFAULT_TIME_INTERVAL);
    assertEquals(ds.getWriteBufferSize(),
        DiskStoreFactory.DEFAULT_WRITE_BUFFER_SIZE);
    assertEquals(ds.getDiskDirs().length, 1);
    assertEquals(ds.getDiskDirs()[0].getAbsolutePath(), new File(".")
        .getAbsolutePath());
    assertNotNull(ds);
    s = conn.createStatement();
    s.execute("drop DiskStore testDiskStore1");
  }

  public void testDiskStoreConfig1() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    File f1 = new File("dir1");
    f1.mkdir();
    File f2 = new File("dir2");
    f2.mkdir();
    File f3 = new File("dir3");
    f3.mkdir();
    s
        .execute("create DiskStore testDiskStore2 maxlogsize 448 autocompact false "
            + " allowforcecompaction true compactionthreshold 80 TimeInterval 23344 "
            + "Writebuffersize 192923 queuesize 1734  ('dir1' 456, 'dir2', 'dir3' 55556 )");
    DiskStore ds = Misc.getGemFireCache().findDiskStore("TESTDISKSTORE2");
    assertNotNull(ds);

    assertEquals(ds.getAllowForceCompaction(), true);
    assertEquals(ds.getAutoCompact(), false);
    assertEquals(ds.getCompactionThreshold(), 80);
    assertEquals(448, ds.getMaxOplogSize());
    assertEquals(ds.getQueueSize(), 1734);
    assertEquals(ds.getTimeInterval(), 23344);
    assertEquals(ds.getWriteBufferSize(), 192923);
    assertEquals(ds.getDiskDirs().length, 3);
    Set<String> files = new HashSet<String>();
    files.add(new File(".", "dir1").getAbsolutePath());
    files.add(new File(".", "dir2").getAbsolutePath());
    files.add(new File(".", "dir3").getAbsolutePath());

    assertEquals(ds.getDiskDirs().length, 3);

    for (File file : ds.getDiskDirs()) {
      assertTrue(files.remove(file.getAbsolutePath()));
    }
    assertTrue(files.isEmpty());
    List<Long> sizes = new ArrayList<Long>();
    int i = 0;
    sizes.add(456l);
    sizes.add(0l);
    sizes.add(55556l);
    for (long size : ds.getDiskDirSizes()) {
      assertEquals(size, sizes.get(i++).longValue());
    }
    //cleanUpDirs(new File[] { f1, f2, f3 });
    s.execute("drop DiskStore testDiskStore2");

  }

  public void testDiskStoreConfig2() throws SQLException
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    File f1 = new File("dir1");
    f1.mkdir();
    File f2 = new File("dir2");
    f2.mkdir();
    s
        .execute("create DiskStore testDiskStore3 ('dir1', 'dir2') WriteBufferSize 7878");
    DiskStore ds = Misc.getGemFireCache().findDiskStore("TESTDISKSTORE3");

    assertEquals(ds.getAllowForceCompaction(),
        DiskStoreFactory.DEFAULT_ALLOW_FORCE_COMPACTION);
    assertEquals(ds.getAutoCompact(), DiskStoreFactory.DEFAULT_AUTO_COMPACT);
    assertEquals(ds.getCompactionThreshold(),
        DiskStoreFactory.DEFAULT_COMPACTION_THRESHOLD);
    assertEquals(ds.getMaxOplogSize(),DiskStoreFactory.DEFAULT_MAX_OPLOG_SIZE);
    assertEquals(ds.getQueueSize(), DiskStoreFactory.DEFAULT_QUEUE_SIZE);
    assertEquals(ds.getTimeInterval(), DiskStoreFactory.DEFAULT_TIME_INTERVAL);
    assertEquals(ds.getWriteBufferSize(), 7878);
    assertEquals(ds.getDiskDirs().length, 2);
    Set<String> files = new HashSet<String>();
    files.add(new File(".", "dir1").getAbsolutePath());
    files.add(new File(".", "dir2").getAbsolutePath());
    assertEquals(ds.getDiskDirs().length, 2);

    for (File file : ds.getDiskDirs()) {
      assertTrue(files.remove(file.getAbsolutePath()));
    }
    assertTrue(files.isEmpty());
    assertNotNull(ds);
    s = conn.createStatement();
    s.execute("drop DiskStore testDiskStore3");

    //cleanUpDirs(new File[] { f1, f2 });
  }

  public void testSysDiskstoreTable() throws Exception
  {
    Connection conn = getConnection();
    Statement s = conn.createStatement();
    File f1 = new File(".", "dir1");
    f1.mkdir();
    File f2 = new File(".", "dir2");
    f2.mkdir();
    File f3 = new File(".", "dir3");
    f3.mkdir();
    s
        .execute("create DiskStore testDiskStore1 maxlogsize 128 autocompact false "
            + " allowforcecompaction true compactionthreshold 80 TimeInterval 23344 "
            + "Writebuffersize 192923 queuesize 1734  ('dir1' 456, 'dir2', 'dir3' 55556 )");

    ResultSet rs = s.executeQuery("select * from SYS.SYSDISKSTORES where "
        + "NAME = 'TESTDISKSTORE1'");
    ResultSetMetaData rsmd = rs.getMetaData();
    assertEquals(rsmd.getColumnCount(), 9);
    for (int i = 1; i < 10; ++i) {
      String name = rsmd.getColumnName(i);
      switch (i) {
      case 1:
        assertEquals("NAME", name);
        break;
      case 2:
        assertEquals("MAXLOGSIZE", name);
        break;
      case 3:
        assertEquals("AUTOCOMPACT", name);
        break;
      case 4:
        assertEquals("ALLOWFORCECOMPACTION", name);
        break;
      case 5:
        assertEquals("COMPACTIONTHRESHOLD", name);
        break;
      case 6:
        assertEquals("TIMEINTERVAL", name);
        break;
      case 7:
        assertEquals("WRITEBUFFERSIZE", name);
        break;
      case 8:
        assertEquals("QUEUESIZE", name);
        break;
      case 9:
        assertEquals("DIR_PATH_SIZE", name);
        break;

      }
    }
    int numRows = 0;
    while (rs.next()) {
      ++numRows;
      assertEquals("TESTDISKSTORE1", rs.getString("NAME"));
      assertEquals("TESTDISKSTORE1", rs.getString(1));
      assertEquals(128, rs.getLong("MAXLOGSIZE"));
      assertEquals(128, rs.getLong(2));
      assertEquals("false", rs.getString("AUTOCOMPACT"));
      assertEquals("false", rs.getString(3));
      assertEquals("true", rs.getString("ALLOWFORCECOMPACTION"));
      assertEquals("true", rs.getString(4));
      assertEquals(80, rs.getInt("COMPACTIONTHRESHOLD"));
      assertEquals(80, rs.getInt(5));
      assertEquals(23344, rs.getLong("TIMEINTERVAL"));
      assertEquals(23344, rs.getLong(6));
      assertEquals(192923, rs.getInt("WRITEBUFFERSIZE"));
      assertEquals(192923, rs.getInt(7));
      assertEquals(1734, rs.getInt("QUEUESIZE"));
      assertEquals(1734, rs.getInt(8));
      String str = f1.getAbsolutePath() + "(456)," + f2.getAbsolutePath() + ","
          + f3.getAbsolutePath() + "(55556)";
      assertEquals(str, rs.getString("DIR_PATH_SIZE"));
      assertEquals(str, rs.getString(9));
    }
    assertEquals(1, numRows);
    
    s.execute("drop DiskStore testDiskStore1");

    rs = s.executeQuery("select * from SYS.SYSDISKSTORES where "
        + "NAME = 'TESTDISKSTORE1'");
    numRows = 0;
    while (rs.next()) {
      ++numRows;
    }
    assertEquals(0, numRows);
    rs = s.executeQuery("select * from SYS.SYSDISKSTORES where "
        + "NAME <> 'TESTDISKSTORE1' ");
    Set<String> names = new HashSet<String>();
    names.add(GfxdConstants.GFXD_DD_DISKSTORE_NAME);
    names.add(GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME);
    names.add(GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE);
    numRows = 0;
    while (rs.next()) {
      ++numRows;
      String diskStoreName = rs.getString("NAME");
      assertTrue(names.remove(diskStoreName));
      assertEquals(Boolean
          .toString(DiskStoreFactory.DEFAULT_ALLOW_FORCE_COMPACTION), rs
          .getString("ALLOWFORCECOMPACTION"));
      assertEquals(Boolean.toString(DiskStoreFactory.DEFAULT_AUTO_COMPACT), rs
          .getString("AUTOCOMPACT"));
      assertEquals(DiskStoreFactory.DEFAULT_COMPACTION_THRESHOLD, rs
          .getInt("COMPACTIONTHRESHOLD"));
      if (diskStoreName.equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)) {
        assertEquals(rs.getLong("MAXLOGSIZE"), 10);
      } else if (diskStoreName.equals(GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE)) {
        assertEquals(rs.getLong("MAXLOGSIZE"), 100);
      }
      else {
        assertEquals(rs.getLong("MAXLOGSIZE"),
            DiskStoreFactory.DEFAULT_MAX_OPLOG_SIZE);
      }
      assertEquals(rs.getInt("QUEUESIZE"), DiskStoreFactory.DEFAULT_QUEUE_SIZE);
      assertEquals(rs.getLong("TIMEINTERVAL"),
          DiskStoreFactory.DEFAULT_TIME_INTERVAL);
      assertEquals(rs.getInt("WRITEBUFFERSIZE"),
          DiskStoreFactory.DEFAULT_WRITE_BUFFER_SIZE);

      if (diskStoreName.equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)) {
        assertEquals(rs.getString("DIR_PATH_SIZE"), new File(".",
            "datadictionary").getCanonicalPath());
      } else if (diskStoreName.equals(GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE)) {
        assertEquals(rs.getString("DIR_PATH_SIZE"), new File(".",
            GfxdConstants.SNAPPY_DELTA_SUBDIR).getCanonicalPath());
      } else {
        assertEquals(rs.getString("DIR_PATH_SIZE"), new File(".")
            .getCanonicalPath());
      }
    }
    assertEquals(3, numRows);
    assertTrue(names.isEmpty());
  }

  public void testDataPersistenceOfPRWithRangePartitioning() throws Exception
  {

    String prClause = " partition by range (cid) ( VALUES BETWEEN 0 AND 10,"
        + " VALUES BETWEEN 10 AND 20, VALUES BETWEEN 20 AND 30 )  ";
    dataPersistenceOfPR(prClause);
  }
  
  private void dataPersistenceOfPR(String partitionClause) throws Exception
  {
    Properties props = new Properties();
    Connection conn = TestUtil.getConnection(props);
    char fileSeparator = System.getProperty("file.separator").charAt(0);
    GemFireCacheImpl cache = Misc.getGemFireCache();
    Statement stmt = conn.createStatement();
    if (cache.findDiskStore("TestPersistenceDiskStore") == null) {

      String path = "." + fileSeparator + "test_dir";
      File file = new File(path);
      if (!file.mkdirs() && !file.isDirectory()) {
        throw new DiskAccessException("Could not create directory for "
            + " default disk store : " + file.getAbsolutePath(), (Region)null);
      }
      try {
        Connection conn1;
        conn1 = TestUtil.getConnection();
        Statement stmt1 = conn1.createStatement();
        stmt1.execute("Create DiskStore " + "TestPersistenceDiskStore" + "'"
            + path + "'");
        conn1.close();
      }
      catch (SQLException e) {
        throw GemFireXDRuntimeException.newRuntimeException(null, e);
      }

    }

    stmt.execute("create schema trade");
    stmt
        .execute("create table trade.customers (cid int not null, cust_name varchar(100), tid int, "
            + "primary key (cid))   "
            + partitionClause
            + "  PERSISTENT "
            + "'"
            + "TestPersistenceDiskStore" + "'");
    PreparedStatement ps = conn
        .prepareStatement("insert into trade.customers values (?,?,?)");
    for (int i = 1; i < 31; ++i) {
      ps.setInt(1, i);
      ps.setString(2, "name" + i);
      ps.setInt(3, i);
      ps.executeUpdate();
    }

    conn.close();
    shutDown();

    conn = TestUtil.getConnection();
    stmt = conn.createStatement();

    ResultSet rs = stmt.executeQuery("select * from trade.customers");
    int expected = 465;
    int actual = 0;
    while (rs.next()) {
      int val = rs.getInt(1);
      actual += val;
    }
    assertEquals(expected, actual);
  }
  
  public void testBug42750() throws Exception {

    Properties props = new Properties();
    Connection conn = TestUtil.getConnection(props);
    char fileSeparator = System.getProperty("file.separator").charAt(0);
    Misc.getGemFireCache();
    conn.createStatement();
    String path = "." + fileSeparator + "test_dir";
    File file = new File(path);
      if (!file.mkdirs() && !file.isDirectory()) {
        throw new DiskAccessException("Could not create directory for "
            + " default disk store : " + file.getAbsolutePath(), (Region)null);
      }
     try {
        Connection conn1;
        conn1 = TestUtil.getConnection();
        Statement stmt1 = conn1.createStatement();
        stmt1.execute("Create DiskStore " + "TestPersistenceDiskStore" + "'"
            + path + "'");
        
        conn1.close();
        TestUtil.shutDown();
        conn1 = TestUtil.getConnection();
        stmt1 = conn1.createStatement();
        stmt1.execute("Create DiskStore " + "TestPersistenceDiskStore" + "'"
            + path + "'");
        fail("Disk store creation should fail as the disk store already exists");
      }
      catch (SQLException e) {
        assertEquals(e.getSQLState(),"X0Y68");        
      }

    } 
  
  public void testBug45816() throws Exception {
  // Test DROP DISKSTORE when a table using default diskstore exists in database

     try {
        Connection conn;
        conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();
        // Create a table that uses the default diskstore
        stmt.execute("create table mytab1 (col1 int not null)");

        // Now, create a basic diskstore with no options
        stmt.execute("Create diskstore MYDISKSTORE1");
        
        stmt.execute("Drop diskstore MYDISKSTORE1");

        // Get rid of the table too
        stmt.execute("drop table mytab1");
        conn.close();
      }
      catch (SQLException e) {
        // Any news is bad news.
        throw GemFireXDRuntimeException.newRuntimeException(null, e);
      }
    }

  public void testBug45897() throws Exception {
    // Test DROP DISKSTORE on default diskstore names (should throw sqlstate 0A000)
    // Default diskstore names have embedded hyphens and therefore need delimiting w/quotes

    try {
      Connection conn;
      conn = TestUtil.getConnection();
      Statement stmt = conn.createStatement();
      // Try to drop the default diskstore. Should fail with 0A000.
      stmt.execute("Drop DiskStore " + "\"" +
          GfxdConstants.GFXD_DD_DISKSTORE_NAME + "\"");
      fail("Disk store drop should fail because diskstore is a default one");
    } catch (SQLException e) {
      assertEquals(e.getSQLState(), "0A000");
    }

    // Try the other named default diskstore
    try {
      Connection conn;
      conn = TestUtil.getConnection();
      Statement stmt = conn.createStatement();
      // Try to drop the default diskstore. Should fail with 0A000.
      stmt.execute("Drop DiskStore " + "\"" +
          GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME + "\"");
      fail("Disk store drop should fail because diskstore is a default one");
    } catch (SQLException e) {
      assertEquals(e.getSQLState(), "0A000");
    }
    try {
      Connection conn;
      conn = TestUtil.getConnection();
      Statement stmt = conn.createStatement();
      // Try to drop the default diskstore. Should fail with 0A000.
      stmt.execute("Drop DiskStore " + "\"" +
          GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE + "\"");
      fail("Disk store drop should fail because diskstore is a default one");
    } catch (SQLException e) {
      assertEquals(e.getSQLState(), "0A000");
    }
  }

  public void testCreateDiskStoreDDLUT() throws Exception
  {
    // Test various legal and illegal forms of CREATE DISKSTORE
    // Catch exceptions from illegal syntax
    // Tests still not fixed marked FIXME
    
    // Array of DDL text to execute and sqlstates to expect
    Object[][] CreateDiskStoreUT = {
      { "CREATE DISKSTORE \"GFXD-DD-DISKSTORE\"", "X0Y68" },
      { "CREATE DISKSTORE \"GFXD-DEFAULT-DISKSTORE\"", "X0Y68" },
       { "CREATE DISKSTORE DISKSTORE", null },
       { "CREATE DISKSTORE MYSTORE1", null },
       { "CREATE DISKSTORE MYSTORE1", "X0Y68" },
       { "CREATE DISKSTORE NULL", "42X01" },
       { "CREATE DISKSTORE ''", "42X01" },
       { "CREATE DISKSTORE \"*\"", "0A000" },
       { "CREATE DISKSTORE SYS.MYDISKSTORE", "42X01" },
       { "CREATE DISKSTORE QUEUESIZE QUEUESIZE 5", null },
       { "CREATE DISKSTORE QUEUESIZE2 QUEUESIZE 5 QUEUESIZE 99", "42Y49" },
       { "CREATE DISKSTORE BADSIZE ('MYDIR' -5000)", "42X44" },
       //FIXME { "CREATE DISKSTORE BADSIZE2 ('MYDIR' 0)", "42X44" },
       { "CREATE DISKSTORE BADSIZE3 ('MYDIR' +infinity)", "42X01" },
       { "CREATE DISKSTORE BADSIZE4 ('MYDIR' x'41')", "42X01" },
       { "CREATE DISKSTORE BADSIZE5 ('MYDIR' 2147483648)", "22018" },
       { "CREATE DISKSTORE BADSIZE6 ('MYDIR' 2147483647)", null },
       { "CREATE DISKSTORE ML1 MAXLOGSIZE -5", "42X44" },
       //FIXME { "CREATE DISKSTORE ML1 MAXLOGSIZE 0", "42X44" },
       { "CREATE DISKSTORE ML3 MAXLOGSIZE 2147483647", null },
       { "CREATE DISKSTORE ML4 MAXLOGSIZE 'hello'", "42X01" },
       { "CREATE DISKSTORE AC1 AUTOCOMPACT true", null },
       { "CREATE DISKSTORE AC2 AUTOCOMPACT false", null },
       { "CREATE DISKSTORE AC3 AUTOCOMPACT 5", "42X01" },
       { "CREATE DISKSTORE AC4 AUTOCOMPACT maybe", "42X01" },
       { "CREATE DISKSTORE AC5 AUTOCOMPACT 'true'", "42X01" },
       { "CREATE DISKSTORE AC6 AUTOCOMPACT \"false\"", "42X01" },
       { "CREATE DISKSTORE AFC1 ALLOWFORCECOMPACTION true", null },
       { "CREATE DISKSTORE AFC2 ALLOWFORCECOMPACTION false", null },
       { "CREATE DISKSTORE AFC3 ALLOWFORCECOMPACTION 11", "42X01" },
       { "CREATE DISKSTORE AFC4 ALLOWFORCECOMPACTION maybe", "42X01" },
       { "CREATE DISKSTORE AFC5 ALLOWFORCECOMPACTION 'true'", "42X01" },
       { "CREATE DISKSTORE AFC6 ALLOWFORCECOMPACTION \"false\"", "42X01" },
       { "CREATE DISKSTORE AFC7 ALLOWFORCECOMPACTION true AUTOCOMPACT false", null },  // should probably be an error
       { "CREATE DISKSTORE CT1 COMPACTIONTHRESHOLD -5", "42X44" },
       { "CREATE DISKSTORE CT2 COMPACTIONTHRESHOLD 0", null },
       { "CREATE DISKSTORE CT3 COMPACTIONTHRESHOLD 100", null },
       { "CREATE DISKSTORE CT4 COMPACTIONTHRESHOLD 55.2", "42X20" },
       { "CREATE DISKSTORE CT5 COMPACTIONTHRESHOLD 175", "0A000" },    // replace with better sqlstate
       { "CREATE DISKSTORE CT6 COMPACTIONTHRESHOLD 'big'", "42X01" },
       { "CREATE DISKSTORE CT7 COMPACTIONTHRESHOLD zero", "42X01" },
       { "CREATE DISKSTORE CT8 COMPACTIONTHRESHOLD 50 AUTOCOMPACT false", null },  // should probably be an error
       { "CREATE DISKSTORE CT9 COMPACTIONTHRESHOLD 50 AUTOCOMPACT false ALLOWFORCECOMPACTION true", null },  // should probably be an error
       { "CREATE DISKSTORE TI1 TIMEINTERVAL -5", "42X44" },
       { "CREATE DISKSTORE TI2 TIMEINTERVAL 0", null },
       { "CREATE DISKSTORE TI3 TIMEINTERVAL 2147473648", null },
       { "CREATE DISKSTORE TI4 TIMEINTERVAL '4'", "42X01" },
       { "CREATE DISKSTORE TI5 TIMEINTERVAL five", "42X01" },
       { "CREATE DISKSTORE WB1 WRITEBUFFERSIZE -5", "42X44" },
       { "CREATE DISKSTORE WB2 WRITEBUFFERSIZE 0", null },
       { "CREATE DISKSTORE WB3 WRITEBUFFERSIZE 2147473648", null },
       { "CREATE DISKSTORE WB4 WRITEBUFFERSIZE 'awholelot'", "42X01" },
       { "CREATE DISKSTORE WB5 WRITEBUFFERSIZE many", "42X01" },
       { "CREATE DISKSTORE QS1 QUEUESIZE -5", "42X44" },
       { "CREATE DISKSTORE QS2 QUEUESIZE 0", null },
       { "CREATE DISKSTORE QS3 QUEUESIZE 2147473648", null },
       { "CREATE DISKSTORE QS4 QUEUESIZE '10'", "42X01" },
       { "CREATE DISKSTORE QS5 QUEUESIZE fifteen", "42X01" },
       { "CREATE DISKSTORE DIRTEST1 ('D1', 'D1')", "X0Z19" },
       { "CREATE DISKSTORE DIRTEST2 ('../D1', '../D1')", "X0Z19" },
       //FIXME { "CREATE DISKSTORE DIRTEST3 ('SUBDIR1', 'SUBDIR2', 'SUBDIR3')", "42X01" },
       //FIXME { "CREATE DISKSTORE DIRTEST4 ('/dev/nul')", "42X01" },   // or some other unwritable directory
       { "CREATE DISKSTORE DIRTEST5 ('')", "0A000" }, 
       //FIXME { "CREATE DISKSTORE DIRTEST6 ('*')", "0A000" }, 
       //FIXME { "CREATE DISKSTORE DIRTEST7 ('?')", "0A000" },    // or any other illegal character
       { "CREATE DISKSTORE DIRTEST8 'DIR1' 700 TIMEINTERVAL 1000 'DIR2' 1000", null }, 
       { "CREATE DISKSTORE DIRTEST9 'DIR1' 0",  "42X01" }, 
       { "CREATE DISKSTORE EVERYTHING1 'DIR1' 700 TIMEINTERVAL 1000 AUTOCOMPACT false QUEUESIZE 75 WRITEBUFFERSIZE 32767 COMPACTIONTHRESHOLD 25 MAXLOGSIZE 699 ALLOWFORCECOMPACTION false", null }, 
       { "CREATE DISKSTORE EVERYTHING2 TIMEINTERVAL 999 WRITEBUFFERSIZE 19776 QUEUESIZE 0 AUTOCOMPACT true COMPACTIONTHRESHOLD 55 ALLOWFORCECOMPACTION true MAXLOGSIZE 125000 ('EVERYTHING1' 500, 'EVERYTHING2' 1500, 'EVERYTHING3')", "X0Z33"},
       { "CREATE DISKSTORE EVERYTHING2 TIMEINTERVAL 999 WRITEBUFFERSIZE 19776 QUEUESIZE 0 AUTOCOMPACT true COMPACTIONTHRESHOLD 55 ALLOWFORCECOMPACTION true MAXLOGSIZE 500 ('EVERYTHING1' 500, 'EVERYTHING2' 1500, 'EVERYTHING3')", null}
    };

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // Go through the array, execute each string[0], check sqlstate [1]
    // This will fail on the first one that succeeds where it shouldn't
    // or throws unknown exception
    JDBC.SQLUnitTestHelper(stmt,CreateDiskStoreUT);
    // TODO : verify columns in catalog
  }
}
