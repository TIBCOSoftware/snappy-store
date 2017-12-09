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
package com.pivotal.gemfirexd.jdbc.transactions;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Properties;
import javax.naming.Context;
import javax.sql.DataSource;
import javax.sql.XAConnection;
import javax.sql.XADataSource;
import javax.transaction.UserTransaction;
import javax.transaction.xa.XAException;
import javax.transaction.xa.XAResource;
import javax.transaction.xa.Xid;

import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.client.ClientXid;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.impl.services.monitor.FileMonitor;
import com.pivotal.gemfirexd.internal.jdbc.EmbeddedXADataSource;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import io.snappydata.jdbc.ClientXADataSource;
import org.apache.derbyTesting.junit.XATestUtil;

public class XATransactionTest extends JdbcTestBase {

  public XATransactionTest(String name) {
    super(name);
  }

  public void testEmbeddedXADataSource() throws Exception {
    EmbeddedXADataSource xaDataSource = (EmbeddedXADataSource)TestUtil
        .getXADataSource(TestUtil.EmbeddedeXADsClassName);
    // create large enough xid
    byte[] gid = new byte[64];
    byte[] bid = new byte[64];
    for (int i = 0; i < 64; i++) {
      gid[i] = (byte)i;
      bid[i] = (byte)(64 - i);
    }
    Xid xid = new ClientXid(0x1234, gid, bid);

    // get the stuff required to execute the global transaction
    try {
      xaDataSource.setDatabaseName("gemfirexd");
      xaDataSource.setConnectionAttributes("mcast-port=2223;portNumber=0");
      FileMonitor monitor = (FileMonitor)Monitor.getCachedMonitorLite(false);
      monitor.report("<ExpectedException action=add>"
          + IllegalArgumentException.class.getName() + "</ExpectedException>");
      XAConnection xaConn = xaDataSource.getXAConnection();
    } catch (SQLException sqle) {
        Throwable t = sqle.getCause();
        IllegalArgumentException expected = null;
        do {
          if(t instanceof IllegalArgumentException) {
            expected = ((IllegalArgumentException)t);
            break;
          }
        }
        while( (t = t.getCause()) != null);
        
        if( expected == null || !expected.getMessage().contains("portNumber")) {
          throw sqle;
        }
    }
    finally {
      FileMonitor monitor = (FileMonitor)Monitor.getCachedMonitorLite(false);
      monitor.report("<ExpectedException action=remove>"
          + IllegalArgumentException.class.getName() + "</ExpectedException>");
    }
    
    xaDataSource.setConnectionAttributes("mcast-port=0");
    XAConnection xaConn = xaDataSource.getXAConnection();

  }

  public void testFromNetClient() throws Exception {    
    setupConnection();
    int netport = startNetserverAndReturnPort("create table XATT2 "
        + "(i int, text char(10))");
    ClientXADataSource xaDataSource = (ClientXADataSource)TestUtil
        .getXADataSource(TestUtil.NetClientXADsClassName);
    byte[] gid = new byte[64];
    byte[] bid = new byte[64];
    for (int i = 0; i < 64; i++) {
      gid[i] = (byte)i;
      bid[i] = (byte)(64 - i);
    }
    Xid xid = new ClientXid(0x1234, gid, bid);

    xaDataSource.setServerName("localhost");
    xaDataSource.setPortNumber(netport);

    xaDataSource.setDatabaseName("gemfirexd");
    // get the stuff required to execute the global transaction
    XAConnection xaConn = xaDataSource.getXAConnection();
    XAResource xaRes = xaConn.getXAResource();
    Connection conn = xaConn.getConnection();

    // start the transaction with that xid
    xaRes.start(xid, XAResource.TMNOFLAGS);

    conn.setTransactionIsolation(getIsolationLevel());
    // do some work
    Statement stm = conn.createStatement();
    stm.execute("insert into XATT2 values (1234, 'Test_Entry')");
    stm.close();

    stm = getConnection().createStatement();
    stm.execute("select * from XATT2");
    ResultSet rs = stm.getResultSet();
    assertFalse(rs.next());
    // end the work on the transaction branch
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.commit(xid, false);
    stm.execute("select * from XATT2");
    rs = stm.getResultSet();
    assertTrue(rs.next());
  }

  public void testJTA_localTransaction() throws Exception {

    Properties props = new Properties();
    String jndiBindingsXmlFile = TestUtil.getResourcesDir()
        + "/lib/gfxdCacheJta.xml";
    props.put("cache-xml-file", jndiBindingsXmlFile);
    Connection c = TestUtil.getConnection(props);
    Statement stmnt = c.createStatement();
    stmnt.execute("create table XATT2 (i int primary key, text char(10))");
    
    String tableName = "testtable";

    Context ctx = Misc.getGemFireCache().getJNDIContext();
    String sql = "create table " + tableName + " (id integer NOT NULL primary key, name varchar(50))";
    DataSource ds = (DataSource) ctx.lookup("java:/SimpleDataSource");
    Connection cxn = ds.getConnection();
    Statement sm = cxn.createStatement();
    try {
      sm.execute("drop table " + tableName);
    } catch (Exception e) {
      // ignore. we just want that table is not present
    }
    sm.execute(sql);
    sm.close();

    UserTransaction ta = (UserTransaction)ctx.lookup("java:/UserTransaction");
    ta.begin();

    Connection sm_conn = null;
    // get the SimpleDataSource before the transaction begins
    DataSource sds = (DataSource)ctx.lookup("java:/SimpleDataSource");

    // Begin the user transaction

    DataSource gemfirexdXADataSource = (DataSource)ctx.lookup("java:/GemfirexdXADataSource");
    Connection conn = gemfirexdXADataSource.getConnection();
    Statement stm = conn.createStatement();
    stm.execute("insert into XATT2 values (1234, 'Test_Entry')");
    stm.close();

    sm_conn = sds.getConnection();

    Statement sm_stmt = sm_conn.createStatement();

    sm_stmt.execute("insert into " + tableName + " values (1, 'first')");

    sm_stmt.close();

    // close the connections
    //xa_conn.close();
    sm_conn.close();

    // commit the transaction
    Connection connBeforeCommit = TestUtil.getConnection();
    stmnt = connBeforeCommit.createStatement();
    stmnt.execute("select * from XATT2");
    assertFalse(stmnt.getResultSet().next());
    stmnt.execute("select * from XATT2 where i = 1234");
    assertFalse(stmnt.getResultSet().next());

    ta.commit();
    
    stmnt.execute("select * from XATT2");
    assertTrue(stmnt.getResultSet().next());
  }
  
  public void testSimpleXATransaction() throws Exception {
    Statement stm = getConnection().createStatement();
    stm.execute("create table XATT2 (i int, text char(10))");

    XADataSource xaDataSource = (XADataSource)TestUtil.getXADataSource(TestUtil.EmbeddedeXADsClassName);
    // create large enough xid
    byte[] gid = new byte[64];
    byte[] bid = new byte[64];
    for (int i = 0; i < 64; i++) {
      gid[i] = (byte)i;
      bid[i] = (byte)(64 - i);
    }
    Xid xid = new ClientXid(0x1234, gid, bid);

    // get the stuff required to execute the global transaction
    XAConnection xaConn = xaDataSource.getXAConnection();
    XAResource xaRes = xaConn.getXAResource();
    Connection conn = xaConn.getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    // start the transaction with that xid
    xaRes.start(xid, XAResource.TMNOFLAGS);

    // do some work
    stm = conn.createStatement();
    stm.execute("insert into XATT2 values (1234, 'Test_Entry')");
    stm.close();

    stm = getConnection().createStatement();
    stm.execute("select * from XATT2");
    ResultSet rs = stm.getResultSet();
    assertFalse(rs.next());
    // end the work on the transaction branch
    xaRes.end(xid, XAResource.TMSUCCESS);
    xaRes.prepare(xid);
    xaRes.commit(xid, false);
    stm.execute("select * from XATT2");
    rs = stm.getResultSet();
    assertTrue(rs.next());
  }

  public void testSingleConnectionOnePhaseCommit() throws SQLException,
      XAException {

    Statement stm = getConnection().createStatement();
    stm.execute("create table XATT2 (i int, text char(10))");

    XADataSource xads = (XADataSource)TestUtil.getXADataSource(TestUtil.EmbeddedeXADsClassName);

    XAConnection xac = xads.getXAConnection();

    XAResource xar = xac.getXAResource();

    Xid xid = XATestUtil.getXid(0, 32, 46);

    xar.start(xid, XAResource.TMNOFLAGS);

    Connection conn = xac.getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, conn.getHoldability());

    Statement s = conn.createStatement();
    assertEquals(ResultSet.CLOSE_CURSORS_AT_COMMIT, s.getResultSetHoldability());

    s.execute("create table foo (a int)");
    s.executeUpdate("insert into foo values (0)");

    ResultSet rs = s.executeQuery("select * from foo");
    assertTrue(rs.next());
    assertFalse(rs.next());

    String[][] expectedRows = { { "(0", "ACTIVE", "false", "APP",
        "UserTransaction" } };

    // TODO: what to check
    // XATestUtil.checkXATransactionView(conn, expectedRows);

    s.close();
    xar.end(xid, XAResource.TMSUCCESS);

    // 1 phase commit
    xar.commit(xid, true);

    conn.close();
    xac.close();
  }

  public void testInterleavingTransactions() throws SQLException, XAException {

    Statement stm = getConnection().createStatement();
    stm.execute("create table foo (a int)");

    XADataSource xads = (XADataSource)TestUtil.getXADataSource(TestUtil.EmbeddedeXADsClassName);

    XAConnection xac = xads.getXAConnection("sku", "testxa");
    XAResource xar = xac.getXAResource();

    Xid xid1 = XATestUtil.getXid(1, 93, 18);
    Xid xid2 = XATestUtil.getXid(2, 45, 77);

    xar.start(xid1, XAResource.TMNOFLAGS);

    Connection conn = xac.getConnection();
    conn.setTransactionIsolation(getIsolationLevel());
    Statement s = conn.createStatement();
    s.executeUpdate("insert into APP.foo values (1)");
    xar.end(xid1, XAResource.TMSUSPEND);

    xar.start(xid2, XAResource.TMNOFLAGS);
    s.executeUpdate("insert into APP.foo values (2)");
    xar.end(xid2, XAResource.TMSUSPEND);

    xar.start(xid1, XAResource.TMRESUME);
    s.executeUpdate("insert into APP.foo values (3)");
    xar.end(xid1, XAResource.TMSUSPEND);

    xar.start(xid2, XAResource.TMRESUME);
    s.executeUpdate("insert into APP.foo values (4)");

    String[][] expectedRows = {
        { "(1", "ACTIVE", "false", "SKU", "UserTransaction" },
        { "(2", "ACTIVE", "false", "SKU", "UserTransaction" } };

    // XATestUtil.checkXATransactionView(conn, expectedRows);

    // this prepare won't work since
    // transaction 1 has been suspended - XA_PROTO
    try {
      xar.prepare(xid1);
      fail("FAIL - prepare on suspended transaction");
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_PROTO)
        XATestUtil
            .dumpXAException("FAIL - prepare on suspended transaction", e);

    }

    // check it was not prepared

    // XATestUtil.checkXATransactionView(conn, expectedRows);

    xar.end(xid2, XAResource.TMSUCCESS);

    xar.end(xid1, XAResource.TMSUCCESS);

    xar.prepare(xid1);
    xar.prepare(xid2);

    // both should be prepared.
    expectedRows = new String[][] {
        { "(1", "PREPARED", "false", "SKU", "UserTransaction" },
        { "(2", "PREPARED", "false", "SKU", "UserTransaction" } };

    // XATestUtil.checkXATransactionView(conn, expectedRows);

    Xid[] recoveredStart = xar.recover(XAResource.TMSTARTRSCAN);
    assertEquals(2, recoveredStart.length);
    Xid[] recovered = xar.recover(XAResource.TMNOFLAGS);
    assertEquals(0, recovered.length);
    Xid[] recoveredEnd = xar.recover(XAResource.TMENDRSCAN);
    assertEquals(0, recoveredEnd.length);

    for (int i = 0; i < recoveredStart.length; i++) {
      Xid xid = recoveredStart[i];
      if (xid.getFormatId() == 1) {
        // commit 1 with 2pc
        xar.commit(xid, false);
      }
      else if (xid.getFormatId() == 2) {
        xar.rollback(xid);
      }
      else {
        fail("FAIL: unknown xact");
      }
    }

    // check the results
    Xid xid3 = XATestUtil.getXid(3, 2, 101);
    xar.start(xid3, XAResource.TMNOFLAGS);
    expectedRows = new String[][] { { "(3", "IDLE", "NULL", "SKU",
        "UserTransaction" } };

    xar.end(xid3, XAResource.TMSUCCESS);

    int pr = xar.prepare(xid3);

    try {
      xar.commit(xid3, false);
      // fail("FAIL - 2pc commit on read-only xact");
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }

    s.close();
    conn.close();
    xac.close();
  }

  public void testNoTransaction() throws SQLException, XAException {
    Statement stm = getConnection().createStatement();
    stm.execute("create table foo (a int)");

    XADataSource xads = (XADataSource)TestUtil.getXADataSource(TestUtil.EmbeddedeXADsClassName);
    XAConnection xac = xads.getXAConnection();
    XAResource xar = xac.getXAResource();

    Xid xid11 = XATestUtil.getXid(11, 3, 128);

    try {
      xar.start(xid11, XAResource.TMJOIN);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }

    try {
      xar.start(xid11, XAResource.TMRESUME);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }

    try {
      xar.end(xid11, XAResource.TMSUCCESS);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }
    try {
      xar.end(xid11, XAResource.TMFAIL);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }

    try {
      xar.end(xid11, XAResource.TMSUSPEND);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }

    try {
      xar.prepare(xid11);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }
    try {
      xar.commit(xid11, false);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }
    try {
      xar.commit(xid11, true);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }
    try {
      xar.rollback(xid11);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }
    try {
      xar.forget(xid11);
    } catch (XAException e) {
      if (e.errorCode != XAException.XAER_NOTA)
        throw e;
    }
  }

  protected int getIsolationLevel() {
    return Connection.TRANSACTION_READ_COMMITTED;
  }
}
