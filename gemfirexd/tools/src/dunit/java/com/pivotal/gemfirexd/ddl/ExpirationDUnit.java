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

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;

import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.gemstone.gnu.trove.TIntHashSet;

@SuppressWarnings("serial")
public class ExpirationDUnit extends DistributedSQLTestBase {

  public ExpirationDUnit(String name) {
    super(name);
  }

  // basic check to make sure that the entry expires
  private void checkExpiration(Statement st1) throws Exception {
    st1.execute("truncate table t1");
    st1.execute("insert into t1 values (1, 1, 1)");
    Thread.sleep(14000);
    st1.execute("select col1 from t1");
    ResultSet rs1 = st1.getResultSet();
    assertFalse(rs1.next());
    rs1.close();
  }
  
  // non pk based select
  private void checkNonPKBasedSelect(Statement st1) throws Exception {
    st1.execute("truncate table t1");
    st1.execute("insert into t1 values (1, 1, 1)");
    Thread.sleep(2000);
    st1.execute("select col1 from t1");
    ResultSet rs1 = st1.getResultSet();
    assertTrue(rs1.next());
    Thread.sleep(10000);
    st1.execute("select col1 from t1");
    rs1 = st1.getResultSet();
    assertFalse(rs1.next());
  }
  
  // pk based select  
  private void checkPKBasedSelect(Statement st1) throws Exception {
    st1.execute("truncate table t1");
    st1.execute("insert into t1 values (1, 1, 1)");
    Thread.sleep(2000);
    st1.execute("select col1 from t1 where col1 = 1");
    ResultSet rs1 = st1.getResultSet();
    assertTrue(rs1.next());
    assertEquals(1, rs1.getInt(1));
    long then = System.currentTimeMillis();
    Thread.sleep(8000);
    st1.execute("select col1 from t1 where col1 = 1");
    long delta = System.currentTimeMillis() - then;
    rs1 = st1.getResultSet();
    if (delta < 9000) {
      assertTrue(rs1.next());
      assertEquals(1, rs1.getInt(1));
      assertFalse(rs1.next());
    } else {
      rs1.close();
    }
  }
  
  // non pk based update
  private void checkNonPKBasedUpdate(Statement st1) throws Exception {
    st1.execute("truncate table t1");
    st1.execute("insert into t1 values (1, 1, 1)");
    Thread.sleep(2000);
    st1.execute("update t1 set col2 = 2");
    long then = System.currentTimeMillis();
    Thread.sleep(8000);
    // after parallel run this can cause issue..3 seconds of wait and row is gone.
    st1.execute("select col1 from t1");
    long delta = System.currentTimeMillis() - then;
    ResultSet rs1 = st1.getResultSet();
    if (delta < 9000) {
      assertTrue(rs1.next());
    } else {
      rs1.close();
    }
  }
  
  // pk based update
  private void checkPKBasedUpdate(Statement st1) throws Exception {
    st1.execute("truncate table t1");
    st1.execute("insert into t1 values (1, 1, 1)");
    Thread.sleep(2000);
    st1.execute("update t1 set col2 = 2 where col1 = 1");
    long then = System.currentTimeMillis();
    Thread.sleep(8000);
    st1.execute("select col1 from t1 where col1 = 1");
    long delta = System.currentTimeMillis() - then;
    ResultSet rs1 = st1.getResultSet();
    if (delta < 9000) {
      assertTrue(rs1.next());
      assertEquals(1, rs1.getInt(1));
      assertFalse(rs1.next());
    } else {
      rs1.close();
    }
  }
  
  private void basicEntryExpirationTest(Connection conn, boolean idletime,
      boolean replicate, boolean persistent, boolean synchronous)
      throws Exception {
    Statement st1 = conn.createStatement();
    
    String distributionPolicy = replicate == true ? 
        "replicate " : "partition by (col1) ";
    String expiryPolicy = idletime == true ?
        "idletime " : "timetolive ";
    
    String createTableStr = "create table t1 (col1 int constraint " +
    		"pk1 primary key, col2 int, col3 int) " + distributionPolicy + 
    		"expire entry with " + expiryPolicy + "10 action destroy" ;
    
    if (persistent) {
      createTableStr = createTableStr + " persistent";
      if (synchronous) {
        createTableStr = createTableStr + " synchronous";
      } else {
        createTableStr = createTableStr + " asynchronous";
      }
    }
    
    st1.execute(createTableStr);
    checkExpiration(st1);
    checkNonPKBasedSelect(st1);
    
    // for idletime should not 
    // expire if the entry is read
    if (!replicate && idletime) {
      checkPKBasedSelect(st1);
    }
    
    checkNonPKBasedUpdate(st1);
    checkPKBasedUpdate(st1);
    st1.execute("drop table t1");
    st1.close();
  }
  
  public void testBasicEntryExpiration() throws Exception {
    // The test is valid only for transaction isolation level NONE. 
    if (isTransactional) {
      return;
    }

    startVMs(1, 3);
    Connection conn = TestUtil.getConnection();
    
    // idletime expiration
    // replicated table
    getLogWriter().info("testing for replicated table idletime policy");
    basicEntryExpirationTest(conn, true, true, false, false);
    // replicated persistent asynchronous table
    basicEntryExpirationTest(conn, true, true, true, false);
    // replicated persistent synchronous table
    basicEntryExpirationTest(conn, true, true, true, true);
    // partitioned table
    getLogWriter().info("testing for partitioned table idletime policy");
    basicEntryExpirationTest(conn, true, false, false, false);
    // partitioned persistent asynchronous table
    basicEntryExpirationTest(conn, true, false, true, false);
    // partitioned persistent synchronous table
    basicEntryExpirationTest(conn, true, false, true, true);
    
    // timetolive expiration
    // replicated table
    getLogWriter().info("testing for replicated table time-to-live policy");
    basicEntryExpirationTest(conn, false, true, false, false);
    // replicated persistent asynchronous table
    basicEntryExpirationTest(conn, false, true, true, false);
    // replicated persistent synchronous table
    basicEntryExpirationTest(conn, false, true, true, true);
    // partitioned table
    getLogWriter().info("testing for partitioned table time-to-live policy");
    basicEntryExpirationTest(conn, false, false, false, false);
    // partitioned persistent asynchronous table
    basicEntryExpirationTest(conn, false, false, true, false);
    // partitioned persistent synchronous table
    basicEntryExpirationTest(conn, false, false, true, true);
  }
  
  public void testTimeToLive() throws Exception {
    // The test is valid only for transaction isolation level NONE. 
    if (isTransactional) {
      return;
    }

    startServerVMs(1, 0, null);
    startClientVMs(1, 0, null);
    try {
      serverSQLExecute(1, "drop diskstore teststore");
    } catch (Exception ignore) {
    }
    serverSQLExecute(1, "create diskstore teststore");
    // INVALIDATE not supported in GemFireXD - test changed to use DESTROY
    clientSQLExecute(1, "create table EMP.TESTTABLE_ONE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "REPLICATE PERSISTENT 'teststore' "
        + "EXPIRE ENTRY WITH TIMETOLIVE 10 ACTION DESTROY ");

    clientSQLExecute(1, "create table EMP.TESTTABLE_TWO (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "PARTITION BY COLUMN (ID) PERSISTENT 'teststore' "
        + "EXPIRE ENTRY WITH TIMETOLIVE 10 ACTION DESTROY ");
    clientSQLExecute(1, "insert into EMP.TESTTABLE_ONE values (1, 'hello')");
    clientSQLExecute(1, "insert into EMP.TESTTABLE_TWO values (1, 'hello')");
    Thread.sleep(5000);
    clientSQLExecute(1, "insert into EMP.TESTTABLE_ONE values (2, 'goodbye')");
    clientSQLExecute(1, "insert into EMP.TESTTABLE_TWO values (2, 'goodbye')");
    long start = System.currentTimeMillis();
    stopVMNum(-1);
    restartVMNums(-1);
    // startServerVMs(1, 0, null);
    TIntHashSet expected = new TIntHashSet();
    expected.add(2);

    // some slow systems may already have exceeded the time in restart
    long elapsed = System.currentTimeMillis() - start;
    if (elapsed < 6000) {
      Thread.sleep(6000 - elapsed);
    } else if (elapsed > 10000) {
      expected = null;
    }

    validateResults("EMP.TESTTABLE_ONE", expected);
    validateResults("EMP.TESTTABLE_TWO", expected);
    Thread.sleep(5000);
    validateResults("EMP.TESTTABLE_ONE", null);
    validateResults("EMP.TESTTABLE_TWO", null);

    clientSQLExecute(1, "drop table EMP.TESTTABLE_TWO");
    clientSQLExecute(1, "drop table EMP.TESTTABLE_ONE");
    serverSQLExecute(1, "drop diskstore teststore");
  }

  public void testAlterCommandTimeToLive() throws Exception {
    // The test is valid only for transaction isolation level NONE. 
    if (isTransactional) {
      return;
    }

    startServerVMs(1, 0, null);
    startClientVMs(1, 0, null);
    try {
      serverSQLExecute(1, "drop diskstore teststore");
    } catch (Exception ignore) {
    }
    serverSQLExecute(1, "create diskstore teststore");
    // INVALIDATE not supported in GemFireXD - test changed to use DESTROY
    clientSQLExecute(1, "create table EMP.TESTTABLE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "REPLICATE PERSISTENT 'teststore' "
        + "EXPIRE ENTRY WITH TIMETOLIVE 10 ACTION DESTROY ");

    clientSQLExecute(1, "insert into EMP.TESTTABLE values (1, 'hello')");
    clientSQLExecute(1, "insert into EMP.TESTTABLE values (2, 'goodbye')");

    //alter table set expire entry timetolive
    clientSQLExecute(1, "ALTER TABLE EMP.TESTTABLE "
        + "set EXPIRE ENTRY WITH TIMETOLIVE 20 ACTION DESTROY");

    clientSQLExecute(1, "insert into EMP.TESTTABLE values (3, 'hello1')");
    clientSQLExecute(1, "insert into EMP.TESTTABLE values (4, 'goodbye1')");

    //changed TTL applies only to the entries inserted after alter command
    TIntHashSet expected = new TIntHashSet();
    expected.add(1);
    expected.add(2);
    expected.add(3);
    expected.add(4);

    Thread.sleep(11000);
    validateResults("EMP.TESTTABLE", expected);

    Thread.sleep(10000);
    validateResults("EMP.TESTTABLE", null);

    clientSQLExecute(1, "drop table EMP.TESTTABLE");
    serverSQLExecute(1, "drop diskstore teststore");
  }

  /**
   * Test that idle expiration time is rest on persistent recovery
   * 
   * @throws Exception
   */
  public void testIdleTime() throws Exception {
    // The test is valid only for transaction isolation level NONE. 
    if (isTransactional) {
      return;
    }
    
    startServerVMs(1, 0, null);
    startClientVMs(1, 0, null);
    try {
      serverSQLExecute(1, "drop diskstore teststore");
    } catch (Exception ignore) {
    }
    serverSQLExecute(1, "create diskstore teststore");
    // INVALIDATE not supported in GemFireXD - test changed to use DESTROY
    clientSQLExecute(1, "create table EMP.TESTTABLE_ONE (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "REPLICATE PERSISTENT 'teststore' "
        + "EXPIRE ENTRY WITH IDLETIME 10 ACTION DESTROY ");

    clientSQLExecute(1, "create table EMP.TESTTABLE_TWO (ID int not null, "
        + " DESCRIPTION varchar(1024) not null, primary key (ID))"
        + "PARTITION BY COLUMN (ID) PERSISTENT 'teststore' "
        + "EXPIRE ENTRY WITH IDLETIME 10 ACTION DESTROY ");
    clientSQLExecute(1, "insert into EMP.TESTTABLE_ONE values (1, 'hello')");
    clientSQLExecute(1, "insert into EMP.TESTTABLE_TWO values (1, 'hello')");
    clientSQLExecute(1, "insert into EMP.TESTTABLE_TWO values (3, 'hello')");
    Thread.sleep(5000);
    long start = System.currentTimeMillis();
    clientSQLExecute(1, "insert into EMP.TESTTABLE_ONE values (2, 'goodbye')");
    clientSQLExecute(1, "insert into EMP.TESTTABLE_TWO values (2, 'goodbye')");
    clientSQLExecute(1, "update EMP.TESTTABLE_TWO set DESCRIPTION = 'hi' where ID = 3");
    clientSQLExecute(1, "select * from EMP.TESTTABLE_TWO where ID = 1 ");
    stopVMNum(-1);
    restartVMNums(-1);
    // startServerVMs(1, 0, null);

    TIntHashSet expected = new TIntHashSet();
    expected.add(2);

    // some slow systems may already have exceeded the time in restart
    long elapsed = System.currentTimeMillis() - start;
    if (elapsed < 6000) {
      Thread.sleep(6000 - elapsed);
    } else if (elapsed > 10000) {
      expected = null;
    }

    validateResults("EMP.TESTTABLE_ONE", expected);
    if (expected != null) {
      expected.add(3);
    }
    validateResults("EMP.TESTTABLE_TWO", expected);
    Thread.sleep(5000);
    validateResults("EMP.TESTTABLE_ONE", null);
    validateResults("EMP.TESTTABLE_TWO", null);

    clientSQLExecute(1, "drop table EMP.TESTTABLE_TWO");
    clientSQLExecute(1, "drop table EMP.TESTTABLE_ONE");
    serverSQLExecute(1, "drop diskstore teststore");
  }

  protected void validateResults(String tablename, TIntHashSet expected)
      throws SQLException {
    {
      PreparedStatement pstmt = TestUtil.getPreparedStatement("select * from "
          + tablename);
      ResultSet rs = pstmt.executeQuery();
      if (expected == null) {
        assertFalse(rs.next());
      } else {
        TIntHashSet ids = new TIntHashSet();
        while (rs.next()) {
          ids.add(rs.getInt("ID"));
        }
        ;
        assertEquals(expected, ids);
      }
    }
  }
  
 }
