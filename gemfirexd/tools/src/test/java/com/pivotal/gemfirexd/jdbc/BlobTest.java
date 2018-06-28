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

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.sql.Blob;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Types;
import java.util.Arrays;
import java.util.concurrent.CyclicBarrier;
import java.util.zip.CRC32;

import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.functionTests.tests.jdbc4.BlobClobTestSetup;
import org.apache.derbyTesting.functionTests.tests.jdbc4.ClobTest;
import org.apache.derbyTesting.functionTests.util.streams.CharAlphabet;
import org.apache.derbyTesting.functionTests.util.streams.LoopingAlphabetReader;
import org.apache.derbyTesting.functionTests.util.streams.LoopingAlphabetStream;

public class BlobTest extends JdbcTestBase {

  private static final byte[] BYTES1 = { 0x65, 0x66, 0x67, 0x68, 0x69, 0x69,
      0x68, 0x67, 0x66, 0x65 };

  private static final byte[] BYTES2 = { 0x69, 0x68, 0x67, 0x66, 0x65, 0x65,
      0x66, 0x67, 0x68, 0x69 };

  /** Default row identifier used by the tests. */
  private final int key = -1;

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(BlobTest.class));
  }

  public BlobTest(String name) {
    super(name);
  }

  @Override
  protected String reduceLogging() {
    return "config";
  }

  public void testBlobAsString() throws SQLException {
    setupConnection();
    Statement stmt = getStatement();
    stmt.executeUpdate("create table b (blob blob(3K))" + getSuffix());
    stmt.executeUpdate("insert into b values(cast(X'0031' as blob(3K)))");
    ResultSet rs = stmt.executeQuery("select blob from b");
    assertEquals(true, rs.next());
    assertEquals("0031", rs.getString(1));
    rs.close();
    stmt.close();
  }

  private static final int JOBDATA_LEN = 500000;

  /** Test for projection on LOB/non-LOB columns */
  public void testBug43623() throws Exception {
    setupConnection();
    final int netPort = startNetserverAndReturnPort();
    Connection conn = getNetConnection(netPort, "", null);
    final String tableDDL = "create table QUARTZ_TRIGGERS (TRIGGER_NAME "
        + "varchar(80) not null, TRIGGER_GROUP varchar(80) not null, "
        + "JOB_NAME varchar(80) not null, JOB_GROUP varchar(80) not null, "
        + "IS_VOLATILE smallint, DESCRIPTION varchar(120), "
        + "NEXT_FIRE_TIME bigint, PREV_FIRE_TIME bigint,  PRIORITY integer, "
        + "TRIGGER_STATE varchar(16) not null, TRIGGER_TYPE varchar(8), "
        + "START_TIME bigint, END_TIME bigint, CALENDAR_NAME varchar(80), "
        + "MISFIRE_INSTR integer, JOB_DATA blob, "
        + "primary key(TRIGGER_NAME, TRIGGER_GROUP))"+ getSuffix();

    final Statement stmt = conn.createStatement();
    stmt.execute(tableDDL);

    final int numThreads = 20;
    Thread[] ts = new Thread[numThreads];
    final Exception[] failure = new Exception[1];
    final CyclicBarrier barrier = new CyclicBarrier(numThreads);
    for (int i = 0; i < numThreads; i++) {
      ts[i] = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            final Connection c = getNetConnection(netPort, "", null);
            barrier.await();
            // perform some inserts into the table
            final byte[] jobData = new byte[JOBDATA_LEN];
            PartitionedRegion.rand.nextBytes(jobData);
            final String group = Thread.currentThread().getName();
            try {
              for (int i = 1; i <= 200; i++) {
                insertData_43623_2(c, jobData, group);
                selectData_43623_2(c, group);
                deleteData_43623_2(c, group);
                if ((i % 40) == 0) {
                  Statement s = c.createStatement();
                  ResultSet rs = s
                      .executeQuery("SELECT COUNT(*) FROM QUARTZ_TRIGGERS");
                  rs.next();
                  System.out.println(group + ": completed " + i
                      + " ops with size=" + rs.getInt(1));
                  rs.close();
                  s.close();
                }
              }
            } finally {
              c.close();
            }
          } catch (Exception e) {
            e.printStackTrace();
            failure[0] = e;
          }
        }
      });
    }
    for (int i = 0; i < numThreads; i++) {
      ts[i].start();
    }
    for (int i = 0; i < numThreads; i++) {
      ts[i].join();
    }

    if (failure[0] != null) {
      throw failure[0];
    }
    stmt.execute("delete from QUARTZ_TRIGGERS where 1=1");
    conn.commit();

    // perform some inserts into the table
    final byte[] jobData = new byte[10000000];
    insertData_43623(conn, jobData);
    // now fire some selects (get convertible, non-get convertible)
    checkQueries_43623(conn, jobData);

    // now do the same for partitioned table on pk
    stmt.execute("drop table QUARTZ_TRIGGERS");
    this.waitTillAllClear();
    stmt.execute(tableDDL + " partition by primary key");
    insertData_43623(conn, jobData);
    // now fire some selects (get convertible, non-get convertible)
    checkQueries_43623(conn, jobData);

    // now do the same for partitioned table on non-pk
    stmt.execute("drop table QUARTZ_TRIGGERS");
    this.waitTillAllClear();
    stmt.execute(tableDDL + " partition by column (JOB_NAME)");
    insertData_43623(conn, jobData);
    // now fire some selects (get convertible, non-get convertible)
    checkQueries_43623(conn, jobData);

    // now the same as above in a transaction
    stmt.execute("drop table QUARTZ_TRIGGERS");
    this.waitTillAllClear();
    stmt.execute(tableDDL);
    insertData_43623(conn, jobData);
    // now fire some selects (get convertible, non-get convertible)
    // checking for transactional data
    checkQueries_43623(conn, jobData);
    conn.commit();
    // now fire some selects (get convertible, non-get convertible)
    // after commit
    checkQueries_43623(conn, jobData);
    conn.commit();

    stmt.execute("drop table QUARTZ_TRIGGERS");
    this.waitTillAllClear();
    stmt.execute(tableDDL + " partition by primary key");
    insertData_43623(conn, jobData);
    // now fire some selects (get convertible, non-get convertible)
    // checking for transactional data
    checkQueries_43623(conn, jobData);
    conn.commit();
    // now fire some selects (get convertible, non-get convertible)
    // after commit
    checkQueries_43623(conn, jobData);
    conn.commit();

    // now do the same for partitioned table on non-pk
    stmt.execute("drop table QUARTZ_TRIGGERS");
    this.waitTillAllClear();
    stmt.execute(tableDDL + " partition by column (JOB_NAME)");
    insertData_43623(conn, jobData);
    // now fire some selects (get convertible, non-get convertible)
    // checking for transactional data
    checkQueries_43623(conn, jobData);
    conn.commit();
    // now fire some selects (get convertible, non-get convertible)
    // after commit
    checkQueries_43623(conn, jobData);
    conn.commit();
  }

  /** Test for bug #42711 from derby's ResultSetTest. */
  public void testUpdateBinaryStream() throws Exception {
    setupConnection();
    final Statement stmt = getStatement();
    stmt.execute("create table UpdateTestTableResultSet ("
        + "sno int not null unique," + "dBlob BLOB," + "dClob CLOB,"
        + "dLongVarchar LONG VARCHAR," + "dLongBit LONG VARCHAR FOR BIT DATA)"+ getSuffix());

    // Byte array in which the returned bytes from
    // the Database after the update are stored. This
    // array is then checked to determine if it
    // has the same elements of the Byte array used for
    // the update operation

    byte[] bytes_ret = new byte[10];

    // Input Stream inserted initially
    InputStream is = new java.io.ByteArrayInputStream(BYTES1);

    // InputStream that is used for update
    InputStream is_for_update = new java.io.ByteArrayInputStream(BYTES2);

    // Prepared Statement used to insert the data
    PreparedStatement ps_sb = prep("dLongBit");
    ps_sb.setInt(1, key);
    ps_sb.setBinaryStream(2, is, BYTES1.length);
    ps_sb.executeUpdate();
    ps_sb.close();

    // Update operation
    // use a different ResultSet variable so that the
    // other tests can go on unimpacted

    ResultSet rs1 = fetchUpd("dLongBit", key);
    rs1.next();
    rs1.updateBinaryStream(1, is_for_update, BYTES2.length);
    rs1.updateRow();
    rs1.close();

    // Query to see whether the data that has been updated
    // using the updateBinaryStream method is the same
    // data that we expected

    rs1 = fetch("dLongBit", key);
    rs1.next();
    InputStream is_ret = rs1.getBinaryStream(1);

    is_ret.read(bytes_ret);
    is_ret.close();

    for (int i = 0; i < BYTES2.length; i++) {
      assertEquals("Error in updateBinaryStream", BYTES2[i], bytes_ret[i]);
    }
    rs1.close();

    stmt.execute("drop table UpdateTestTableResultSet");
    this.waitTillAllClear();
    stmt.close();
  }

  /**
   * Adapted from Derby's BlobClob4BlobTest#testGetBinaryStream().
   */
  public void testGetBinaryStream() throws Exception {
    setupConnection();
    createBlobClobTables();
    insertDefaultData();
    Statement stmt = getStatement();
    ResultSet rs = stmt.executeQuery("select a, b, crc32 from testBlob");
    checkBlobContents(rs);
    stmt.close();
  }

  /**
   * Adapted from Derby's BlobClobTestSetup#testFreeandMethodsAfterCallingFree
   */
  public void testFreeandMethodsAfterCallingFree() throws Exception {
    setupConnection();
    createBlobClobTable();
    new ClobTest("tmp").testFreeandMethodsAfterCallingFree();
  }

  /**
   * Prepare commonly used statement to insert a row.
   * 
   * @param colName
   *          name of the column to insert into
   */
  private PreparedStatement prep(String colName) throws SQLException {
    return getPreparedStatement("insert into UpdateTestTableResultSet "
        + "(sno, " + colName + ") values (?,?)");
  }

  /**
   * Fetch the specified row for update.
   * 
   * @param colName
   *          name of the column to fetch
   * @param key
   *          identifier for row to fetch
   * @return a <code>ResultSet</code> with zero or one row, depending on the key
   *         used
   */
  private ResultSet fetchUpd(String colName, int key) throws SQLException {
    Statement stmt = jdbcConn.createStatement(ResultSet.TYPE_FORWARD_ONLY,
        ResultSet.CONCUR_UPDATABLE);
    return stmt.executeQuery("select " + colName
        + " from UpdateTestTableResultSet where sno = " + key + " for update");
  }

  /**
   * Fetch the specified row.
   * 
   * @param colName
   *          name of the column to fetch
   * @param key
   *          identifier for row to fetch
   * @return a <code>ResultSet</code> with zero or one row, depending on the key
   *         used
   */
  private ResultSet fetch(String colName, int key) throws SQLException {
    Statement stmt = getStatement();
    return stmt.executeQuery("select " + colName
        + " from UpdateTestTableResultSet where sno = " + key);
  }

  private void createBlobClobTables() throws SQLException {
    Statement stmt = getStatement();
    stmt.executeUpdate("CREATE TABLE testClob (b INT, c INT)"+ getSuffix());
    stmt.executeUpdate("ALTER TABLE testClob ADD COLUMN a CLOB(300K)");
    //stmt.executeUpdate("CREATE TABLE testClob (a CLOB(300K), b INT, c INT)");

    stmt.executeUpdate("CREATE TABLE testBlob (b INT)"+ getSuffix());
    stmt.executeUpdate("ALTER TABLE testBlob ADD COLUMN a blob(300k)");
    stmt.executeUpdate("ALTER TABLE testBlob ADD COLUMN crc32 BIGINT");
    //stmt.executeUpdate("CREATE TABLE testBlob (a blob(300k), b INT, crc32 BIGINT)");

    stmt.close();
  }

  private void createBlobClobTable() throws SQLException {
    Statement stmt = getStatement();
    stmt.execute("create table BLOBCLOB (ID int primary key, "
        + "BLOBDATA blob," + "CLOBDATA clob)"+ getSuffix());
    stmt.execute("insert into BLOBCLOB VALUES " + "("
        + BlobClobTestSetup.ID_NULLVALUES + ", null, null)");
    // Actual data is inserted in the getSample* methods.
    stmt.execute("insert into BLOBCLOB VALUES " + "("
        + BlobClobTestSetup.ID_SAMPLEVALUES + ", null, null)");
    stmt.close();
  }

  private void insertDefaultData() throws Exception {
    PreparedStatement ps = getPreparedStatement("INSERT INTO testClob "
        + "(a, b, c) VALUES (?, ?, ?)");

    String clobValue = "";
    ps.setString(1, clobValue);
    ps.setInt(2, clobValue.length());
    ps.setLong(3, 0);
    ps.addBatch();
    clobValue = "you can lead a horse to water but you can't form it "
        + "into beverage";
    ps.setString(1, clobValue);
    ps.setInt(2, clobValue.length());
    ps.setLong(3, 0);
    ps.addBatch();
    clobValue = "a stitch in time says ouch";
    ps.setString(1, clobValue);
    ps.setInt(2, clobValue.length());
    ps.setLong(3, 0);
    ps.addBatch();
    clobValue = "here is a string with a return \n character";
    ps.setString(1, clobValue);
    ps.setInt(2, clobValue.length());
    ps.setLong(3, 0);
    ps.addBatch();

    ps.executeBatch();
    ps.clearBatch();

    insertLoopingAlphabetStreamData(ps, CharAlphabet.modernLatinLowercase(), 0);
    insertLoopingAlphabetStreamData(ps, CharAlphabet.modernLatinLowercase(), 56);
    insertLoopingAlphabetStreamData(ps, CharAlphabet.modernLatinLowercase(),
        5000);
    insertLoopingAlphabetStreamData(ps, CharAlphabet.modernLatinLowercase(),
        10000);
    insertLoopingAlphabetStreamData(ps, CharAlphabet.modernLatinLowercase(),
        300000);

    ps.setNull(1, Types.CLOB);
    ps.setInt(2, 0);
    ps.setLong(3, 0);
    ps.executeUpdate();

    ps.close();

    ps = getPreparedStatement("INSERT INTO testBlob (a, b, crc32) "
        + "VALUES (?, ?, ?)");

    byte[] blobValue = "".getBytes("US-ASCII");
    ps.setBytes(1, blobValue);
    ps.setInt(2, blobValue.length);
    ps.setLong(3, getStreamCheckSum(new ByteArrayInputStream(blobValue)));
    ps.addBatch();
    blobValue = ("you can lead a horse to water but you can't form it "
        + "into beverage").getBytes("US-ASCII");
    ps.setBytes(1, blobValue);
    ps.setInt(2, blobValue.length);
    ps.setLong(3, getStreamCheckSum(new ByteArrayInputStream(blobValue)));
    ps.addBatch();
    blobValue = "a stitch in time says ouch".getBytes("US-ASCII");
    ps.setBytes(1, blobValue);
    ps.setInt(2, blobValue.length);
    ps.setLong(3, getStreamCheckSum(new ByteArrayInputStream(blobValue)));
    ps.addBatch();
    blobValue = "here is a string with a return \n character"
        .getBytes("US-ASCII");
    ps.setBytes(1, blobValue);
    ps.setInt(2, blobValue.length);
    ps.setLong(3, getStreamCheckSum(new ByteArrayInputStream(blobValue)));
    ps.addBatch();

    ps.executeBatch();
    ps.clearBatch();

    insertLoopingAlphabetStreamData(ps, 0);
    insertLoopingAlphabetStreamData(ps, 56);
    insertLoopingAlphabetStreamData(ps, 5000);
    insertLoopingAlphabetStreamData(ps, 10000);
    insertLoopingAlphabetStreamData(ps, 300000);

    ps.setNull(1, Types.BLOB);
    ps.setInt(2, 0);
    ps.setNull(3, Types.BIGINT);
    ps.executeUpdate();

    ps.close();
  }

  /**
   * Test the contents of the testBlob table or ResultSet with identical shape.
   */
  public void checkBlobContents(ResultSet rs) throws Exception {
    int nullCount = 0;
    int rowCount = 0;
    byte[] buff = new byte[128];
    // fetch row back, get the long varbinary column as a blob.
    Blob blob;
    int blobLength = 0, i = 0;
    while (rs.next()) {
      i++;
      // get the first column as a clob
      blob = rs.getBlob(1);
      long crc32 = rs.getLong(3);
      boolean crc2Null = rs.wasNull();
      if (blob == null) {
        assertTrue("FAIL - NULL BLOB but non-NULL checksum", crc2Null);
        nullCount++;
      }
      else {
        rowCount++;

        long blobcrc32 = getStreamCheckSum(blob.getBinaryStream());
        assertEquals("FAIL - mismatched checksums for blob with " + "length "
            + blob.length(), blobcrc32, crc32);

        InputStream fin = blob.getBinaryStream();
        int columnSize = 0;
        for (;;) {
          int size = fin.read(buff);
          if (size == -1)
            break;
          columnSize += size;
        }
        blobLength = rs.getInt(2);
        assertEquals("FAIL - wrong column size", blobLength, columnSize);
        assertEquals("FAIL - wrong column length", blobLength, blob.length());
      }
    }
    assertEquals("FAIL - wrong not null row count null:" + nullCount, 9,
        rowCount);
    assertEquals("FAIL - wrong null blob count", 1, nullCount);
  }

  private void insertLoopingAlphabetStreamData(PreparedStatement ps,
      int lobLength) throws Exception {
    ps.setBinaryStream(1, new LoopingAlphabetStream(lobLength), lobLength);
    ps.setInt(2, lobLength);
    ps.setLong(3, getStreamCheckSum(new LoopingAlphabetStream(lobLength)));
    ps.executeUpdate();
  }

  private void insertLoopingAlphabetStreamData(PreparedStatement ps,
      CharAlphabet alphabet, int lobLength) throws Exception {
    ps.setCharacterStream(1, new LoopingAlphabetReader(lobLength, alphabet),
        lobLength);
    ps.setInt(2, lobLength);
    ps.setLong(3, -1);
    ps.executeUpdate();
  }

  /**
   * Get the checksum of a stream, reading its contents entirely and closing it.
   */
  private long getStreamCheckSum(InputStream in) throws Exception {
    CRC32 sum = new CRC32();

    byte[] buf = new byte[32 * 1024];

    for (;;) {
      int read = in.read(buf);
      if (read == -1) {
        break;
      }
      sum.update(buf, 0, read);
    }
    in.close();
    return sum.getValue();
  }

  public static void insertData_43623(final Connection conn,
      final byte[] jobData) throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("insert into "
        + "QUARTZ_TRIGGERS(TRIGGER_NAME, TRIGGER_GROUP, TRIGGER_STATE, "
        + "JOB_NAME, JOB_GROUP, JOB_DATA) values(?, ?, ?, ?, ?, ?)");
    PartitionedRegion.rand.nextBytes(jobData);
    final int numRows = 10;
    for (int cnt = 1; cnt <= numRows; cnt++) {
      pstmt.setString(1, "trig" + cnt);
      pstmt.setString(2, "grp" + cnt);
      pstmt.setString(3, "st" + cnt);
      pstmt.setString(4, "job" + cnt);
      pstmt.setString(5, "jgrp" + cnt);
      pstmt.setBytes(6, jobData);
      pstmt.execute();
    }
  }

  public static void insertData_43623_2(final Connection conn,
      final byte[] jobData, final String group) throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("insert into "
        + "QUARTZ_TRIGGERS (TRIGGER_NAME, TRIGGER_GROUP, TRIGGER_STATE, "
        + "JOB_NAME, JOB_GROUP, JOB_DATA) values(?, ?, ?, ?, ?, ?)");
    final int numRows = 10;
    for (int cnt = 1; cnt <= numRows; cnt++) {
      pstmt.setString(1, "trig" + cnt);
      pstmt.setString(2, "grp" + '_' + group + '_' + cnt);
      pstmt.setString(3, "st" + cnt);
      pstmt.setString(4, "job" + cnt);
      pstmt.setString(5, "jgrp" + cnt);
      pstmt.setBytes(6, jobData);
      pstmt.execute();
    }
  }

  public static void selectData_43623_2(final Connection conn,
      final String group) throws SQLException {
    PreparedStatement pstmt = conn
        .prepareStatement("select TRIGGER_STATE, JOB_DATA from "
            + "QUARTZ_TRIGGERS where TRIGGER_NAME = ? AND TRIGGER_GROUP = ?");
    final int numRows = 10;
    for (int cnt = 1; cnt <= numRows; cnt++) {
      pstmt.setString(1, "trig" + cnt);
      pstmt.setString(2, "grp" + '_' + group + '_' + cnt);
      ResultSet rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("st" + cnt, rs.getString(1));
      byte[] jobData = rs.getBytes(2);
      assertEquals(JOBDATA_LEN, jobData.length);
      assertFalse(rs.next());
    }
  }

  public static void deleteData_43623_2(final Connection conn,
      final String group) throws SQLException {
    PreparedStatement pstmt = conn.prepareStatement("delete from "
        + "QUARTZ_TRIGGERS where TRIGGER_NAME = ? AND TRIGGER_GROUP = ?");
    final int numRows = 10;
    for (int cnt = 1; cnt <= numRows; cnt++) {
      pstmt.setString(1, "trig" + cnt);
      pstmt.setString(2, "grp" + '_' + group + '_' + cnt);
      pstmt.execute();
    }
  }

  public static void checkQueries_43623(final Connection conn,
      final byte[] jobData) throws Exception {
    PreparedStatement pstmt;
    ResultSet rs;
    final int numRows = 10;
    pstmt = conn.prepareStatement("SELECT TRIGGER_STATE FROM QUARTZ_TRIGGERS "
        + "WHERE TRIGGER_NAME = ? AND TRIGGER_GROUP = ?");
    for (int cnt = 1; cnt <= numRows; cnt++) {
      pstmt.setString(1, "trig" + cnt);
      pstmt.setString(2, "grp" + cnt);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("st" + cnt, rs.getString(1));
      assertFalse(rs.next());
      rs.close();
    }
    pstmt = conn.prepareStatement("SELECT TRIGGER_STATE, JOB_DATA FROM "
        + "QUARTZ_TRIGGERS WHERE TRIGGER_NAME = ? AND TRIGGER_GROUP = ?");
    for (int cnt = 1; cnt <= numRows; cnt++) {
      pstmt.setString(1, "trig" + cnt);
      pstmt.setString(2, "grp" + cnt);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("st" + cnt, rs.getString(1));
      if (!Arrays.equals(jobData, rs.getBytes(2))) {
        fail("LOB column not expected value");
      }
      assertFalse(rs.next());
      rs.close();
    }
    pstmt = conn.prepareStatement("SELECT TRIGGER_STATE FROM QUARTZ_TRIGGERS "
        + "WHERE JOB_NAME = ?");
    for (int cnt = 1; cnt <= numRows; cnt++) {
      pstmt.setString(1, "job" + cnt);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("st" + cnt, rs.getString(1));
      assertFalse(rs.next());
      rs.close();
    }
    pstmt = conn.prepareStatement("SELECT TRIGGER_STATE, JOB_DATA FROM "
        + "QUARTZ_TRIGGERS WHERE JOB_NAME = ?");
    for (int cnt = 1; cnt <= numRows; cnt++) {
      pstmt.setString(1, "job" + cnt);
      rs = pstmt.executeQuery();
      assertTrue(rs.next());
      assertEquals("st" + cnt, rs.getString(1));
      if (!Arrays.equals(jobData, rs.getBytes(2))) {
        fail("LOB column not expected value");
      }
      assertFalse(rs.next());
      rs.close();
    }
  }
  
  public String getSuffix() {
    return " ";
  }
  
  public void waitTillAllClear() {   
  }
}
