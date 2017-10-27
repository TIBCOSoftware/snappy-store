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
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Properties;
import java.util.Vector;

import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreFactoryImpl;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.DDLHoplogOrganizer;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.util.BlobHelper;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import io.snappydata.test.dunit.SerializableRunnable;

/**
 * 
 * @author jianxiachen
 *
 */

@SuppressWarnings("serial")
public class CreateHDFSStoreDUnit extends DistributedSQLTestBase {
  
  public CreateHDFSStoreDUnit(String name) {
    super(name);
  }

  public void testBug51481() throws Exception {
    startVMs(1, 2);

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("create table t1 (col1 int primary key, col2 int) replicate");
    st.execute("create table t2 (col1 int primary key, col2 int) "
        + "partition by primary key");

    final int[] isolations = new int[] {
        Connection.TRANSACTION_NONE,
        Connection.TRANSACTION_READ_COMMITTED,
        Connection.TRANSACTION_REPEATABLE_READ };

    for (int isolation : isolations) {
      conn.setTransactionIsolation(isolation);
      conn.setAutoCommit(true);
      st.execute("put into t1 values (11, 123)");
      st.execute("put into t1 values (11, 123)");

      st.execute("put into t2 values (11, 123)");
      st.execute("put into t2 values (11, 123)");

      // and with putAlls
      st.execute("put into t1 values (11, 123), (11, 123), (12, 124)");
      st.execute("put into t2 values (11, 123), (11, 123), (12, 124)");

      // also without autocommit
      conn.setAutoCommit(false);
      st.execute("put into t1 values (11, 123)");
      st.execute("put into t1 values (11, 123)");
      conn.commit();

      st.execute("put into t2 values (11, 123)");
      st.execute("put into t2 values (11, 123)");
      conn.commit();

      // and with putAlls
      st.execute("put into t1 values (11, 123), (11, 123), (12, 124)");
      st.execute("put into t2 values (11, 123), (11, 123), (12, 124)");
      conn.commit();
    }
  }

  public void testBug49893() throws Exception {
    
    // Start one client a two servers
    startVMs(1, 2);        

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    
    try {
      st.execute("create table t1 (col1 int primary key, col2 int) replicate enable concurrency checks");
      st.execute("insert into t1 values (11, 123)");
      PreparedStatement ps = conn.prepareStatement("insert into t1 values (?,?)");
      for (int i = 0; i < 1; i++) {
        int col1 = 11 + i;
        ps.setInt(1, col1);
        ps.setInt(2, i);
        ps.addBatch();
      }
      ps.executeBatch();
      fail("Should throw duplicate primary key exception");
    } catch (SQLException e) {
      if (e.getSQLState().compareTo("23505") != 0) {
        throw e;
      }     
    }  
    
  }
  
  public void testBug49236() throws Exception {
    Properties props = new Properties();
    int mcastPort = AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS);
    props.put("mcast-port", String.valueOf(mcastPort));    
    Connection conn = TestUtil.getConnection(props);
    Statement st = conn.createStatement();
    st.execute("create schema emp");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir './myhdfs' queuepersistent true");
    
    st.execute("CREATE TABLE emp.Booking (id bigint not null, "
        + "beds int not null, "
        + "primary key (id)) "
        + "PARTITION BY PRIMARY KEY " + 
        "EVICTION BY CRITERIA (ID > 1000 ) " + 
        "EVICTION FREQUENCY 180 SECONDS " + 
        "PERSISTENT " + 
        "HDFSSTORE (myhdfs);");
    
    st.execute ("insert into emp.Booking (id, beds) values (1, 2)");
    ResultSet set = st.executeQuery("select * from emp.Booking");
    int count = 0;
    for (; set.next();) {
      count++;
    }
    assertEquals(1, count);
    
    TestUtil.shutDown();
    conn = TestUtil.getConnection(props);
    st = conn.createStatement();
    st.execute ("insert into emp.Booking (id, beds) values (2, 3)");
    set = st.executeQuery("select * from emp.Booking");
    count = 0;
    for (; set.next();) {
      count++;
    }
    assertEquals(2, count);
    st.execute("drop table emp.Booking");
    st.execute("drop hdfsstore myhdfs");
    delete(new File("./myhdfs"));
    TestUtil.shutDown();
  }

  public void testRestart() throws Exception {
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;

    checkDirExistence(homeDir);
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "'");
    st.execute("create table mytable (id int generated always as identity, " +
        "item char(25)) PARTITION BY COLUMN (ID) PERSISTENT HDFSSTORE (myhdfs);");

    // put as update
    st.execute("insert into mytable values (default, 'widget');");

    //shutdown and restart
    stopAllVMs();
    restartVMNums(-1, -2);
    restartVMNums(1);

    conn = TestUtil.getConnection();
    st = conn.createStatement();
    st.execute("insert into mytable values (default, 'widget1');");
    st.execute("drop table mytable");
    st.execute("drop hdfsstore myhdfs"); 
    delete(homeDirFile);
  }

  public void testPutDML() throws Exception {
    
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;
    
    checkDirExistence(homeDir);
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "' BatchTimeInterval 100 milliseconds ");
    st.execute("create table app.m1 (col1 int primary key , col2 int) hdfsstore (myhdfs)");
    
    // check impact on GfxdIndexManager
    st.execute("create index app.idx1 on app.m1(col2)");
    st.execute("insert into app.m1 values (11, 22)");
    
    // put as update
    st.execute("put into app.m1 values (11, 33)");
    // put as insert
    st.execute("put into app.m1 values (66, 77)");
    
    //verify      
    st.execute("select * from app.m1 where col2 = 22");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    st.execute("select * from app.m1 where col2 = 33");
    rs = st.getResultSet();
    assertTrue(rs.next());    
    assertEquals(33, rs.getInt(2));
    assertFalse(rs.next());
    
    st.execute("select * from app.m1 where col2 = 77");
    rs = st.getResultSet();
    assertTrue(rs.next());    
    assertEquals(77, rs.getInt(2));
    assertFalse(rs.next());
    
    st.execute("select * from app.m1");
    rs = st.getResultSet();             
    int count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(2, count);
    
    //make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/M1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    
    //shutdown and restart
    stopAllVMs();    
    restartVMNums(-1, -2);
    restartVMNums(1);
    
    conn = TestUtil.getConnection();
    st = conn.createStatement();
    
    //verify     
    st.execute("select * from app.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = 11");
    rs = st.getResultSet();
    assertTrue(rs.next());    
    assertEquals(33, rs.getInt(2));
    assertFalse(rs.next());
    
    st.execute("select * from app.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = 66");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(77, rs.getInt(2));
    assertFalse(rs.next());
    
    st.execute("select * from app.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    rs = st.getResultSet();
    count = 0;
    while (rs.next()) {
      count++;
    }
    assertEquals(2, count);
    
    st.execute("drop table app.m1");
    st.execute("drop hdfsstore myhdfs"); 
    delete(homeDirFile);
  }
  
  public void testBug52073() throws Exception {

    // Start one client a two servers
    startVMs(1, 2);

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    st.execute("CREATE TABLE VOLTE.D_SUBSCRIPTION ("
        + "imsi varchar(64) not null, " + "market varchar(32), "
        + "constraint volte_d_subscription_imsi_pk primary key (imsi)) "
        + "PARTITION BY COLUMN(imsi) " + "REDUNDANCY 1 "
        + "PERSISTENT ASYNCHRONOUS");

    String putStmt = "PUT INTO VOLTE.D_SUBSCRIPTION VALUES (?, ?)";
    PreparedStatement ps = conn.prepareStatement(putStmt);
    for (int i = 0; i < 2; i++) {
      ps.setString(1, String.valueOf(i));
      ps.setString(2, "abc");
      ps.addBatch();
    }
    int[] ret = ps.executeBatch();
    for (int i = 0; i < 2; i++) {
      assertEquals(1, ret[i]);
    }

    // Do it again, see if there is EntryExistsException
    for (int i = 0; i < 2; i++) {
      ps.setInt(1, i);
      ps.setString(2, "abc");
      ps.addBatch();
    }
    ret = ps.executeBatch();
    for (int i = 0; i < 2; i++) {
      assertEquals(1, ret[i]);
    }
  }

  public void testPutDMLSubSelect() throws Exception {
    
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;
    
    checkDirExistence(homeDir);
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "' BatchTimeInterval 100 milliseconds ");
    st.execute("create table app.m1 (col1 int primary key , col2 int) hdfsstore (myhdfs)");
    
    // check impact on GfxdIndexManager
    st.execute("create index app.idx1 on app.m1(col2)");
    st.execute("insert into app.m1 values (11, 22)");
    
    st.execute("create table app.m2 (col1 int primary key, col2 int)");
    st.execute("insert into app.m2 values (11, 12), (22, 23), (33, 34)");
    
    //put with sub-select
    st.execute("put into app.m1 select * from app.m2");
       
    //verify      
    st.execute("select * from app.m1 where col2 = 22");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    st.execute("select * from app.m1 where col2 = 12");
    rs = st.getResultSet();
    assertTrue(rs.next());    
    assertEquals(11, rs.getInt(1));
    assertFalse(rs.next());
    
    st.execute("select * from app.m1 where col2 = 23");
    rs = st.getResultSet();
    assertTrue(rs.next());    
    assertEquals(22, rs.getInt(1));
    assertFalse(rs.next());
    
    st.execute("select count(*) from app.m1");
    rs = st.getResultSet();             
    assertTrue(rs.next()); 
    assertEquals(3, rs.getInt(1));
    
    //make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/M1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");
    
    //shutdown and restart
    stopAllVMs();    
    restartVMNums(-1, -2);
    restartVMNums(1);
    
    conn = TestUtil.getConnection();
    st = conn.createStatement();
    
    //nothing in memory
    st.execute("select * from app.m1");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    //verify     
    st.execute("select * from app.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = 11");
    rs = st.getResultSet();
    assertTrue(rs.next());    
    assertEquals(12, rs.getInt(2));
    assertFalse(rs.next());
    
    st.execute("select * from app.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n where col1 = 33");
    rs = st.getResultSet();
    assertTrue(rs.next());
    assertEquals(34, rs.getInt(2));
    assertFalse(rs.next());
    
    st.execute("select count(*) from app.m1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n");
    rs = st.getResultSet();        
    assertTrue(rs.next());
    assertEquals(3, rs.getInt(1));
    assertFalse(rs.next());
    
    st.execute("drop table app.m1");
    st.execute("drop hdfsstore myhdfs"); 
    delete(homeDirFile);
  }
  
  public void testPutDMLSubSelectReplicated() throws Exception {
    // Start one client a two servers
    startVMs(1, 2);        

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;
    
    st.execute("create table t1 (col1 int primary key, col2 int) replicate");
    st.execute("create table t2 (col1 int primary key, col2 int) partition by primary key");
    st.execute("insert into t2 values (1, 11), (2, 22)");
    st.execute("put into t1 select * from t2");
    st.execute("put into t1 select * from t2");
    
  }
  
  public void testTruncateTableHDFS() throws Exception {
    
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;
       
    checkDirExistence(homeDir);
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "' BatchTimeInterval 100 milliseconds");
    st.execute("create table app.t1 (col1 int primary key, col2 int) hdfsstore (myhdfs)");
    
    PreparedStatement ps = conn.prepareStatement("insert into app.t1 values (?, ?)");
    int NUM_ROWS = 100;
    for (int i = 0; i < NUM_ROWS; i++) {
      ps.setInt(1, i);
      ps.setInt(2, i + 1);
      ps.executeUpdate();
    }

    String qname = HDFSStoreFactoryImpl.getEventQueueName("/APP/T1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    //shutdown and restart to clean the memory
    stopAllVMs();    
    restartVMNums(-1, -2);
    restartVMNums(1);
    
    conn = TestUtil.getConnection();
    
    st = conn.createStatement();
    st.execute("select * from app.t1");
    rs = st.getResultSet();
    //nothing in memory
    assertFalse(rs.next());
        
    //now test with query hint to enable HDFS iterator, make sure data is in HDFS
    st.execute("select * from app.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n order by col1");
    rs = st.getResultSet();
    checkHDFSIteratorResultSet(rs, NUM_ROWS);
    
    // TRUNCATE TABLE should also remove data in HDFS
    st.execute("truncate table app.t1");
    
    //now query again without the query hint, it should return nothing
    st.execute("select * from app.t1");
    rs = st.getResultSet();
    assertFalse(rs.next());
    
    //now test with query hint to enable HDFS iterator again, it should return nothing 
    st.execute("select * from app.t1 -- GEMFIREXD-PROPERTIES queryHDFS=true \n order by col1");
    rs = st.getResultSet();
    checkHDFSIteratorResultSet(rs, 0);
    
    st.execute("drop table app.t1");
    st.execute("drop hdfsstore myhdfs");
    delete(homeDirFile);
  }
  
  public void testDistributionOfHDFSStoreCreate() throws Exception {
    
    // Start one client a two servers
    startVMs(1, 2);

    checkDirExistence("./gemfire");
    clientSQLExecute(1, "create hdfsstore TEST namenode 'localhost'");
    
    // Test the HDFSStore presence on servers by using SYS.SYSHDFSSTORES
    sqlExecuteVerify(null, new int[] { 1, 2 },
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "ddl-dist1");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "ddl-dist1");
    
    // No HDFS Store on client
    clientExecute(1, verifyNoHDFSStoreExistence("TEST"));
    
    // check the HDFS Store by GemFire Cache
    SerializableRunnable verifier = verifyHDFSStoreExistence("TEST");
    for (int i = 1; i < 3; ++i) {
      serverExecute(i, verifier);
    }
    
    // add one server and check the HDFS store existence by SYS.SYSHDFSSTORES
    startVMs(0, 1);
    sqlExecuteVerify(null, new int[] { 3 },
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "ddl-dist1");
    serverExecute(3, verifier);
    
    // check HDFS Store on all three servers by SYS.SYSHDFSSTORES
    sqlExecuteVerify(null, new int[] { 1, 2, 3 },
        "select NAME from SYS.SYSHDFSSTORES  ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "ddl-dist1");
    
    // check the client
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "ddl-dist1");
    
    //drop the hdfs store
    clientSQLExecute(1, "drop hdfsstore TEST");
    
    // No HDFS Store on client
    clientExecute(1, verifyNoHDFSStoreExistence("TEST"));
    
    // check the HDFS Store by GemFire Cache
    verifier = verifyNoHDFSStoreExistence("TEST");
    for (int i = 1; i < 3; ++i) {
      serverExecute(i, verifier);
    }
    
    // check HDFS Store on all three servers by SYS.SYSHDFSSTORES
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "empty");
    sqlExecuteVerify(null, new int[] { 1, 2, 3 },
        "select NAME from SYS.SYSHDFSSTORES  ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "empty");    
    HDFSStoreImpl hs = Misc.getGemFireCache().findHDFSStore("TEST");
    assertNull(hs);
    delete(new File("./gemfire"));
  }

  public void testDistributionOfHDFSStoreDrop() throws Exception {
    
    // Start one client a two servers
    startVMs(1, 2);

    checkDirExistence("./gemfire");
    clientSQLExecute(1, "create hdfsstore TEST namenode 'localhost'");
    
    // Test the HDFS Store presence on servers
    sqlExecuteVerify(null, new int[] { 1, 2 },
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "ddl-dist1");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "ddl-dist1");
    clientExecute(1, verifyNoHDFSStoreExistence("TEST"));
    serverExecute(1, verifyHDFSStoreExistence("TEST"));
    serverExecute(2, verifyHDFSStoreExistence("TEST"));
    
    clientSQLExecute(1, "drop hdfsstore TEST");
    serverExecute(1, verifyNoHDFSStoreExistence("TEST"));
    serverExecute(2, verifyNoHDFSStoreExistence("TEST"));
    
    //add a new server
    startVMs(0, 1);
    sqlExecuteVerify(null, new int[] { 3 },
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "empty");
    serverExecute(3, verifyNoHDFSStoreExistence("TEST"));
    sqlExecuteVerify(null, new int[] { 1, 2, 3 },
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST'",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "empty");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "empty");
    
    delete(new File("./gemfire"));
  }

  public void testPersistentAttributesForAccessor() throws Exception {
    
    startVMs(1, 2);

    final File homeDirFile = new File(".", "teststore");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement s = conn.createStatement();
    s.execute("create schema trade");
    
    checkDirExistence(homeDir);
    s.execute("create hdfsstore teststore namenode 'localhost' homedir '" +
        homeDir + "'");

    clientExecute(1, verifyNoHDFSStoreExistence("TESTSTORE"));
    serverExecute(1, verifyHDFSStoreExistence("TESTSTORE"));
    serverExecute(2, verifyHDFSStoreExistence("TESTSTORE"));
    
    String persistentSuffix = "persistent hdfsstore (teststore)";
    
    // Table is PR and range partition
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "addr varchar(100), tid int, primary key (cid))   "
            + "partition by range (cid) (VALUES BETWEEN 0.0 AND 99.0)  "
            + persistentSuffix);   
    
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      RegionAttributes ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());
    }
    finally {
      s.execute("drop table trade.customers");      
    }
    
    //Table is PR with default partition   
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))   "            
            + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      RegionAttributes ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());    
    }
    finally {
      s.execute("drop table trade.customers");
    }

    //Table is PR with partition by PK
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid)) partition by primary key   "            
            + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      RegionAttributes ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());
    }
    finally {
      s.execute("drop table trade.customers");
    }

    //Table is PR with partition by column
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" +
            " partition by column (cust_name)  " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      RegionAttributes ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());
    }
    finally {
      s.execute("drop table trade.customers");
    }
    
    //Table is PR with partition by List
    s.execute("create table trade.customers (cid decimal(30, 20), cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY LIST ( tid ) ( VALUES (10, 20 )," 
            + " VALUES (50, 60), VALUES (12, 34, 45)) " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      RegionAttributes ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());    
    }
    finally {
      s.execute("drop table trade.customers");
    }
    
  //Table is PR  Partition by column colocated with another table
    
  //TODO: java.lang.IllegalStateException: Colocated regions should have same parallel gateway sender ids
    
    /*
    s.execute("create table trade.customers (cid int , cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY column ( cid )  " + persistentSuffix);
    s.execute("create table trade.orders (oid decimal(30, 20), amount int, "
        + " tid int, cid int, primary key (oid))" 
        + "PARTITION BY column ( cid )  colocate with ( trade.customers) " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS", true);
      RegionAttributes ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());

      rgn = Misc.getRegionForTable("TRADE.ORDERS", true);
      ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());

    }
    finally {
//      s.execute("drop table trade.orders");
//      s.execute("drop table trade.customers");
    }
    
  //Table is PR  Partition by column colocated with another table       
    
    s.execute("create table trade.customers2 (cid int , cust_name varchar(100), "
            + "since date, addr varchar(100), tid int, primary key (cid))" 
            + "PARTITION BY column ( cid )  " + persistentSuffix);
    s.execute("create table trade.orders2 (oid decimal(30, 20), amount int, "
        + " tid int, cid int, primary key (oid))" 
        + "PARTITION BY column ( cid )  colocate with ( trade.customers2) " + persistentSuffix);
    try {
      Region rgn = Misc.getRegionForTable("TRADE.CUSTOMERS2", true);
      RegionAttributes ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());

      rgn = Misc.getRegionForTable("TRADE.ORDERS2", true);
      ra = rgn.getAttributes();
      assertEquals(DataPolicy.HDFS_PARTITION, ra.getDataPolicy());

    }
    finally {
//      s.execute("drop table trade.orders2");
//      s.execute("drop table trade.customers2");
    }*/
    s.execute("drop hdfsstore teststore");
    delete(homeDirFile);
  } 

  public void testDDLPersistenceFromClientOnMultipleServers() throws Exception {
    
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfsfromclient");
    final String homeDir = homeDirFile.getAbsolutePath();

    clientSQLExecute(1, "create schema emp");
    checkDirExistence(homeDir);
    clientSQLExecute(1, "create hdfsstore test namenode 'localhost' homedir '" +
        homeDir + "'");
    clientSQLExecute(1, "create table emp.mytab1 (col1 int primary key, col2 int) persistent hdfsstore (test)");
    shutDownAll();
    startVMs(2, 3);

    serverSQLExecute(3, "insert into emp.mytab1 values (1,2) ");
    serverSQLExecute(3, "insert into emp.mytab1 values (2,2) ");
    
    //add a new server
    serverSQLExecute(3, "create alias a1 for 'b1'");
    clientSQLExecute(1, "create table emp.mytab2 (col1 int primary key, col2 int) persistent hdfsstore (test)");
    serverSQLExecute(1, "drop table emp.mytab1 ");
    // the store should be created on the servers
    sqlExecuteVerify(null, new int[] { 1 , 2, 3},
                "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
                TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "ddl-dist1");
    // the store should not be created on the clients
    sqlExecuteVerify(new int[] { 1 , 2}, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "ddl-dist1");
    clientExecute(1, verifyNoHDFSStoreExistence("TEST"));
    clientExecute(2, verifyNoHDFSStoreExistence("TEST"));

    serverExecute(1, verifyDDLPersistence("TEST"));
    serverExecute(2, verifyDDLPersistence("TEST"));
    serverExecute(3, verifyDDLPersistence("TEST"));
    serverSQLExecute(1, "drop table emp.mytab2 ");
    clientSQLExecute(1, "drop hdfsstore test ");
    // the store should be deleted 
    sqlExecuteVerify(new int[] { 1 , 2}, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "empty");

    delete(homeDirFile);
  }
  
 public void testDDLPersistenceFromClientOnSingleServer() throws Exception {
    
    // Start one client and one server
    startVMs(1, 1);

   final File homeDirFile = new File(".", "myhdfsfromclient");
   final String homeDir = homeDirFile.getAbsolutePath();

    clientSQLExecute(1, "create schema emp");
    checkDirExistence(homeDir);
    clientSQLExecute(1, "create hdfsstore test namenode 'localhost' homedir '" +
        homeDir + "'");
    clientSQLExecute(1, "create table emp.mytab1 (col1 int primary key, col2 int) persistent hdfsstore (test)");
    serverSQLExecute(1, "create alias a1 for 'b1'");
    clientSQLExecute(1, "create table emp.mytab2 (col1 int primary key, col2 int) persistent hdfsstore (test)");
    
    serverSQLExecute(1, "drop table emp.mytab1 ");
    // the store should be created on the servers
    sqlExecuteVerify(null, new int[] { 1 },
                "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
                TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "ddl-dist1");
    sqlExecuteVerify(new int[] { 1 }, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "ddl-dist1");
    
    serverExecute(1, verifyDDLPersistence("TEST"));
    serverSQLExecute(1, "drop table emp.mytab2 ");
    clientSQLExecute(1, "drop hdfsstore test ");
    delete(homeDirFile);
  }

 public void testDDLPersistenceFromClientWithNoServer() throws Exception {
   startVMs(1, 0);
   boolean exceptionthrown = false;
   try {
     clientSQLExecute(1, "create schema emp");
   }catch (Exception e) {
     exceptionthrown = true;
   }
   assertTrue ("The command should have failed as there is no server", exceptionthrown);
 }

  public void testBulkDMLInsertsForHDFSQueue() throws Exception {
    
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfsfromclient");
    final String homeDir = homeDirFile.getAbsolutePath();

    clientSQLExecute(1, "create schema emp");
    
    checkDirExistence(homeDir);
    clientSQLExecute(1, "create hdfsstore clienttest namenode 'localhost' homedir '" +
        homeDir + "'");
    clientSQLExecute(1, "create table emp.mytab1 (col1 int primary key, col2 int) persistent hdfsstore (clienttest)");
    
    clientSQLExecute(1, "Insert into emp.mytab1 values(1,1)");
    clientSQLExecute(1, "Insert into emp.mytab1 values(2,2)");
    clientSQLExecute(1, "Insert into emp.mytab1 values(3,3)");
    // Before 48589 was fixed, this function logged class cast exceptions.  
    clientExecute(1, execute());
    clientSQLExecute(1, "drop table emp.mytab1");
    clientSQLExecute(1, "drop hdfsstore clienttest");
    delete(homeDirFile);
  }

  public void testEstimateSize() throws Exception {
    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    Connection conn = TestUtil.getConnection();
    Statement st = conn.createStatement();
    ResultSet rs = null;
    
    checkDirExistence(homeDir);
    st.execute("create schema hdfs");
    st.execute("create hdfsstore myhdfs namenode 'localhost' homedir '" +
        homeDir + "'  BatchTimeInterval 100 milliseconds");
    st.execute("create table hdfs.m1 (col1 int primary key , col2 int) hdfsstore (myhdfs)");
    
    // check impact on GfxdIndexManager
//    st.execute("create index idx1 on m1(col2)");
    st.execute("insert into hdfs.m1 values (11, 22)");
    
    // put as update
    st.execute("put into hdfs.m1 values (11, 33)");
    // put as insert
    st.execute("put into hdfs.m1 values (66, 77)");
    
    //make sure data is written to HDFS
    String qname = HDFSStoreFactoryImpl.getEventQueueName("/HDFS/M1");
    st.execute("CALL SYS.WAIT_FOR_SENDER_QUEUE_FLUSH('" + qname + "', 1, 0)");

    //shutdown and restart
    stopAllVMs();    
    restartVMNums(-1, -2);
    restartVMNums(1);
    
    conn = TestUtil.getConnection();
    st = conn.createStatement();
    st.execute("values COUNT_ESTIMATE('hdfs.m1')");
    rs = st.getResultSet();
    int count = 0;
    while (rs.next()) {
      count++;
      assertEquals(2, rs.getLong(1));
    }
    assertEquals(1, count);
    st.execute("drop table hdfs.m1");
    st.execute("drop hdfsstore myhdfs"); 
    delete(homeDirFile);
  }

  private SerializableRunnable execute() throws Exception{
    return new SerializableRunnable() { 
      @Override
          public void run() {
          
        try {
          PreparedStatement pstmt = TestUtil.jdbcConn
              .prepareStatement("update emp.mytab1 set col2 = ? ");
          pstmt.setInt(1, 100);
          pstmt.executeUpdate();
        } catch (Exception e) {
          // TODO Auto-generated catch block
          e.printStackTrace();
        }
        
      }
    };
    
  }
  
  private SerializableRunnable verifyDDLPersistence(final String name) {
    return new SerializableRunnable() {
      @Override
      public void run() {
        ArrayList<DDLConflatable> ddlconflatables = null;
        
        try {
          HDFSStoreImpl hdfsStore = Misc.getGemFireCache().findHDFSStore(name);
          ddlconflatables = getDDLConflatables(hdfsStore);
        } catch (Exception e) {
          Misc.getGemFireCache().getLoggerI18n().fine("EXCEPTION " + e);
        }
        assertEquals("Unexpected DDLs: " + ddlconflatables,
            5, ddlconflatables.size());
        assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create schema"));
        assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create hdfsstore"));
        assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
        assertTrue(ddlconflatables.get(3).getValueToConflate().startsWith("create alias"));
        assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("create table"));
        
      }
      private ArrayList<DDLConflatable> getDDLConflatables(HDFSStoreImpl store) throws IOException,
      ClassNotFoundException {
        DDLHoplogOrganizer organizer = store.getDDLHoplogOrganizer();
        
        ArrayList<byte[]> ddls = organizer.getDDLStatementsForReplay().getDDLStatements();
        ArrayList<DDLConflatable> ddlconflatables = new ArrayList<DDLConflatable>();
        for (byte[] ddl : ddls) {
          ddlconflatables.add((DDLConflatable)BlobHelper.deserializeBlob(ddl));
        }
        return ddlconflatables;
      }
    };
  }
 
  private SerializableRunnable verifyHDFSStoreExistence(final String name) {
    return new SerializableRunnable() {
      @Override
      public void run() {
        assertNotNull(Misc.getGemFireCache().findHDFSStore(name));
      }
    };
  }

  private SerializableRunnable verifyNoHDFSStoreExistence(final String name) {
    return new SerializableRunnable() {
      @Override
      public void run() {
        assertNull(Misc.getGemFireCache().findHDFSStore(name));
      }
    };
  }
  

  // check HDFS iterator with ORDER BY clause
  // First, check if all expected results are returned
  // Second, check if results are ordered
  private void checkHDFSIteratorResultSet(ResultSet rs, int expectedSize)
      throws Exception {
    Vector<Object> v = new Vector<Object>();
    while (rs.next()) {
      v.add(rs.getObject(1));
    }
    Object[] arr = v.toArray();
    Arrays.sort(arr);
    assertEquals(expectedSize, arr.length);
    for (int i = 0; i < expectedSize; i++) {
      assertEquals(i, ((Integer) arr[i]).intValue());
    }
  }

  // Assume no other thread creates the directory at the same time
  private void checkDirExistence(String path) {
    File dir = new File(path);
    if (dir.exists()) {
      delete(dir);
    }
  }
}
