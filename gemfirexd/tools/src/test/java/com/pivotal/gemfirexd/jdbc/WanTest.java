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
import java.sql.*;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;

import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueFactoryImpl;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.wan.GatewayReceiver;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.SocketCreator;
import com.gemstone.gemfire.internal.cache.AbstractRegion;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.wan.GatewaySenderConfigurationException;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.AsyncEventListener;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.GfxdGatewayEventListener;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.WanProcedures;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import io.snappydata.test.dunit.DistributedTestBase;
import junit.framework.Assert;
import junit.framework.TestSuite;
import junit.textui.TestRunner;
import org.apache.derbyTesting.junit.JDBC;

public class WanTest extends JdbcTestBase {

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(WanTest.class));
  }

  public WanTest(String name) {
    super(name);
  }

  public static void myTest(Integer param) {
    System.out.println("param=" + param);
  }

  protected String getCreateTestTableSQL() {
    return "create table TESTTABLE (ID int not null primary key, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024)) "
        + "AsyncEventListener (" + currentTest + ")";
  }

  protected String getCreateTestTableWithoutAsyncEventListenerSQL() {
    return "create table TESTTABLE (ID int not null primary key, "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024))";
  }
  
  protected String getCreateAppSG1TestTableSQL() {
    return "create table APP1_SG1.TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024)) "
        + "AsyncEventListener (" + currentTest + ")";
  }
  
  protected String getCreateAppSG2TestTableSQL() {
    return "create table APP2_SG2.TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024)) "
        + "AsyncEventListener (" + currentTest + ")";
  }
  
  protected String getSQLSuffixClause() {
    return "";
  }
  
  /**
   * Call this to wait for the asynchronous event queue to free its reference counts for 
   * dispatched off-heap queue event data.
   */
  protected void waitForAsyncQueueToDrain(final String asyncQueueBackingRegion) {    
    final Region<?,?> region = Misc.getRegion(asyncQueueBackingRegion, true, false);
    DistributedTestBase.waitForCriterion(new DistributedTestBase.WaitCriterion() {
      public boolean done() {
        return region.isEmpty();
      }
      public String description() {
        return "Waiting for the backing region of the AsyncEventQueue to drain.";
      }
    }, 5000, 500, true);
  }
  
  public void testTableCreationWithAsyncEventListener() throws SQLException {
    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = getConnection(info);
    JdbcTestBase.addAsyncEventListener("SG1", currentTest,
        "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListener",
        new Integer(10), null, null, null, null, Boolean.TRUE, Boolean.TRUE, null, "test-init-param");
    Statement stmt = conn.createStatement();
    stmt.execute(getCreateTestTableSQL());
    final Region<?, ?> rgn = Misc.getGemFireCache().getRegion(
        '/' + getCurrentDefaultSchemaName() + "/TESTTABLE");
    final String listenerId = currentTest.toUpperCase();
    assertTrue(rgn.getAttributes().getEnableGateway());
    assertTrue(rgn.getAttributes().getAsyncEventQueueIds().toString(), rgn
        .getAttributes().getAsyncEventQueueIds().contains(listenerId));
    AsyncEventQueue asyncQueue = Misc.getGemFireCache().getAsyncEventQueue(
        listenerId);
    assertNotNull(asyncQueue);
    assertFalse(asyncQueue.isRunning());
    assertFalse(asyncQueue.isPrimary());
    JdbcTestBase.startAsyncEventListener(currentTest);
    assertTrue(asyncQueue.isRunning());
    assertTrue(asyncQueue.isPrimary());
    assertTrue(asyncQueue.isPersistent());
    assertTrue(asyncQueue.isDiskSynchronous());
    TestGatewayEventListener tgen = (TestGatewayEventListener)this
        .getGatewayListenerForWBCL(currentTest).getAsyncEventListenerForTest();
    assertEquals(tgen.test, "test-init-param");
  }

  public void testAsyncEventListenerStop() throws SQLException {
    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = getConnection(info);
    JdbcTestBase.addAsyncEventListenerWithConn("SG1", currentTest,
        "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListener",
        new Integer(10), null, null, null, null, Boolean.FALSE, Boolean.FALSE, null, null,
        null);
    Statement stmt = conn.createStatement();
    stmt.execute(getCreateTestTableSQL());
    final Region<?, ?> rgn = Misc.getGemFireCache().getRegion(
        '/' + getCurrentDefaultSchemaName() + "/TESTTABLE");
    assertTrue(rgn.getAttributes().getEnableGateway());
    final String listenerId = currentTest.toUpperCase();
    assertTrue(rgn.getAttributes().getAsyncEventQueueIds().toString(), rgn
        .getAttributes().getAsyncEventQueueIds().contains(listenerId));
    AsyncEventQueue asyncQueue = Misc.getGemFireCache().getAsyncEventQueue(
        listenerId);
    assertNotNull(asyncQueue);
    assertFalse(asyncQueue.isRunning());
    assertFalse(asyncQueue.isPrimary());
    JdbcTestBase.startAsyncEventListener(currentTest);
    assertTrue(asyncQueue.isRunning());
    assertTrue(asyncQueue.isPrimary());
    JdbcTestBase.stopAsyncEventListener(currentTest);
    assertFalse(asyncQueue.isRunning());
  }

  private GfxdGatewayEventListener getGatewayListenerForWBCL(String wbclID) {
    AsyncEventQueue asyncQueue = Misc.getGemFireCache().getAsyncEventQueue(
        wbclID.toUpperCase());
    return (GfxdGatewayEventListener)asyncQueue.getAsyncEventListener();
  }

  public void testNoAsyncEventListenerCreatedDueToServerGroupMismatch()
      throws SQLException {
    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    getConnection(info);
    try {
      JdbcTestBase.addAsyncEventListenerWithConn("SG2", currentTest,
          "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListener",
          new Integer(10), null, null, null, null, null, null, null, null, null);
      fail("expected exception due to no datastore in SG1");
    }
    catch (SQLException sqle) {
      if (!"X0Z08".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }

  public void testPersistentAsyncEventListenerQueue() throws Exception {
    
    // Bug #51785
    if (isTransactional) {
      return;
    }
    
    // Configure a AsyncEventListener
    // Attach it to a table.
    // Make batch size as 1, insert two rows & hold the processor thread
    // so that second sits in the queue
    // Disconnect the system. Restart it & wait for processor to process event
    // in the
    // queue
    String dirStr = "." + File.separatorChar + currentTest;
    final File dir = new File(dirStr);
    try {
      Properties info = new Properties();
      info.setProperty("server-groups", "SG1");
      Connection conn = getConnection(info);

      cleanUpDirs(new File[] { dir });
      Statement stmt = conn.createStatement();
      stmt.execute("create diskstore test ( '" + dirStr + "' )");

      JdbcTestBase.addAsyncEventListener("SG1", currentTest,
          "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListenerNotify",
          new Integer(50), null, null, null, "test", Boolean.TRUE, Boolean.TRUE, null,
          "false");
      TestGatewayEventListenerNotify tgen = (TestGatewayEventListenerNotify)this
          .getGatewayListenerForWBCL(currentTest).getAsyncEventListenerForTest();

      JdbcTestBase.startAsyncEventListener(currentTest);

      stmt.execute(getCreateTestTableSQL());

      stmt.execute("Insert into TESTTABLE values(1,'desc1','Add1')");

      stmt.execute("Insert into TESTTABLE values(2,'desc1','Add2')");
      // was added for debugging purposes
      //stmt.execute("Update TESTTABLE set ADDRESS = 'Add3' where DESCRIPTION='desc1'");
      
      // Wait for event to arrive in Gateway Queue
      while (true) {
        Event[] events = tgen.getEventsCollected();
        if (events == null) {
          Thread.sleep(500);
          continue;
        }
        Set temp = new HashSet();
        for (Event ev : events) {
          temp.add(ev);

        }
        if (temp.size() == 2) {
          Iterator<Event> itr = temp.iterator();
          Set keys = new HashSet();
          keys.add(1);
          keys.add(2);
          assertTrue(keys.remove(itr.next().getPrimaryKey()[0]));
          assertTrue(keys.remove(itr.next().getPrimaryKey()[0]));
          break;
        }
        Thread.sleep(500);
      }
      try {
        shutDown();
      }
      catch (Exception ignore) {

      }

      info = new Properties();
      loadDriver();
      info.setProperty("server-groups", "SG1");
      conn = getConnection(info);

      tgen = (TestGatewayEventListenerNotify)this.getGatewayListenerForWBCL(
          currentTest).getAsyncEventListenerForTest();

      conn = getConnection(info);

      while (true) {
        Event[] events = tgen.getEventsCollected();
        if (events == null) {
          Thread.sleep(500);
          continue;
        }
        Set temp = new HashSet();
        for (Event ev : events) {
          temp.add(ev);

        }
        if (temp.size() == 2) {
          Iterator<Event> itr = temp.iterator();
          Set keys = new HashSet();
          keys.add(1);
          keys.add(2);
          assertTrue(keys.remove(itr.next().getPrimaryKey()[0]));
          assertTrue(keys.remove(itr.next().getPrimaryKey()[0]));
          break;
        }
        Thread.sleep(500);
      }
      stopAsyncEventListener(currentTest);
    }
    finally {
      cleanUpDirs(new File[] { dir });
    }
  }

  public void testAsyncEventListenerAPI() throws Exception {
    // Bug #51835
    if (isTransactional) {
      return;
    }

    // Configure a AsyncEventListener
    // Attach it to a table.
    // Make batch size as 1, insert two rows & hold the processor thread
    // so that second sits in the queue
    // Disconnect the system. Restart it & wait for processor to process event
    // in the
    // queue
    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = getConnection(info);
    JdbcTestBase
        .addAsyncEventListener("SG1", currentTest,
            "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListenerNotify",
            new Integer(10), null, false, null, null, Boolean.FALSE, Boolean.FALSE, null,
            "true,4");
    JdbcTestBase.startAsyncEventListener(currentTest);
    TestGatewayEventListenerNotify tgen = (TestGatewayEventListenerNotify)this
        .getGatewayListenerForWBCL(currentTest).getAsyncEventListenerForTest();

    Statement stmt = conn.createStatement();
    stmt.execute(getCreateTestTableWithoutAsyncEventListenerSQL());
    
    stmt.execute("alter table TESTTABLE set AsyncEventListener (" + currentTest + ')');

    stmt.execute("Insert into TESTTABLE values(1,'desc1','Add1')");
    stmt.execute("Insert into TESTTABLE values(2,'desc2','Add2')");
    stmt.execute("update TESTTABLE set description = 'modified' where id =2");
    synchronized (TestGatewayEventListenerNotify.class) {
      stmt.execute("delete from testtable where ID = 1");
      // Wait for event to arrive in Gateway Queue
      TestGatewayEventListenerNotify.class.wait(30000);
    }
    boolean isTransactional = (conn.getTransactionIsolation() != Connection.TRANSACTION_NONE);
    Event events[] = tgen.getEventsCollected();
    int i = 1;
    for (Event ev : events) {
      assertNotNull(ev);
      switch (i) {
        case 1: {
          if (isTransactional) {
            // Currently single inserts are received as bulk DML in case of tx 
            assertEquals(Event.Type.BULK_DML, ev.getType());
          } else {
            assertEquals(Event.Type.AFTER_INSERT, ev.getType());
            List list = ev.getNewRow();
            assertEquals(list.size(), 3);
            assertEquals(list.get(0), new Integer(1));
            assertEquals(list.get(1), "desc1");
            assertEquals(list.get(2), "Add1");
            assertNull(ev.getModifiedColumns());
          }
          break;
        }
        case 2: {
          if (isTransactional) {
            assertEquals(Event.Type.BULK_DML, ev.getType());
          } else {
            assertEquals(Event.Type.AFTER_INSERT, ev.getType());
            List list = ev.getNewRow();
            assertEquals(list.size(), 3);
            assertEquals(list.get(0), new Integer(2));
            assertEquals(list.get(1), "desc2");
            assertEquals(list.get(2), "Add2");
            assertNull(ev.getModifiedColumns());
          }
          break;
        }
        case 3: {
          assertEquals(Event.Type.AFTER_UPDATE, ev.getType());
          List list = ev.getNewRow();
          assertEquals(list.size(), 3);
          // assertEquals(list.get(0),new Integer(2));
          assertNull(list.get(0));
          assertEquals(list.get(1), "modified");
          // assertEquals(list.get(2),"Add2");
          assertNull(list.get(2));
          int[] modCols = ev.getModifiedColumns();
          assertNotNull(modCols);
          assertEquals(modCols[0], 2);
          break;
        }
        case 4: {
          assertEquals(Event.Type.AFTER_DELETE, ev.getType());
          List list = ev.getNewRow();
          assertNull(list);
          assertNull(ev.getModifiedColumns());
          break;

        }
      }
      ++i;
    }    
    
    waitForAsyncQueueToDrain("AsyncEventQueue_TESTASYNCEVENTLISTENERAPI_SERIAL_GATEWAY_SENDER_QUEUE");
  }

  // This needs to be modified to test Disk store creation once the ddl/proc is
  // in place
  public void testGfxdBaseDirPathSetViaProperties() throws Exception {
    // set the base dir property via the conenction params & see that it is
    // present as
    // system property
    Properties info = new Properties();
    final char fileSeparator = File.separatorChar;
    String dir = "." + fileSeparator + "testGfxdBaseDirPathSetViaProperties";
    info.setProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, dir);
    info.setProperty("server-groups", "SG1");
    this.deleteDirs = new String[] { dir };
    getConnection(info);
    assertEquals(System.getProperty(GfxdConstants.SYS_PERSISTENT_DIR_PROP), dir);
  }

  public void _testBug41366_1() throws Exception {
    // Make the VM part of two server groups SG1 & SG2.
    // Make two schema's belonging to SG1 & SG2 respectively.
    // Add a WBCL to SG1.
    // Try assosciating the table of SG2 with the WBCL, it should fail.

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1,SG2");
    Connection conn = getConnection(info);
    JdbcTestBase.addAsyncEventListenerWithConn("SG1", currentTest,
        "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListener", null,
        new Integer(10), null, null, null, null, null, null, null, null);
    Statement stmt = conn.createStatement();
    stmt.execute("create schema APP1_SG1 DEFAULT SERVER GROUPS (SG1)");
    stmt.execute("create schema APP2_SG2 DEFAULT SERVER GROUPS (SG2)");
    stmt.execute(getCreateAppSG1TestTableSQL());
    stmt.execute(getCreateAppSG2TestTableSQL());
    Region rgn = Misc.getGemFireCache().getRegion("/APP1_SG1/TESTTABLE");
    assertTrue(rgn.getAttributes().getEnableGateway());
    assertEquals(rgn.getAttributes().getGatewayHubId(), currentTest);

    rgn = Misc.getGemFireCache().getRegion("/APP2_SG2/TESTTABLE");
    assertFalse(rgn.getAttributes().getEnableGateway());
    assertEquals(rgn.getAttributes().getGatewayHubId(), currentTest);
  }

  public void testBug41562_1() throws Exception {
    // Make the VM part of two server groups SG1 & SG2.
    // Make two schema's belonging to SG1 & SG2 respectively.
    // Add a WBCL to SG1.
    // Try assosciating the table of SG2 with the WBCL, it should fail.

    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute(getCreateTestTableSQL());
    try {
      JdbcTestBase.addAsyncEventListenerWithConn(null, currentTest,
          "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListener", null,
          new Integer(10), null, null, null, null, null, null, null, null);
      fail("AsyncEventListener attachment should have thrown exception");
    }
    catch (SQLException sqle) {
      if (!"42X01".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }

  public void testBug41562_2() throws Exception {
    // Make the VM part of two server groups SG1 & SG2.
    // Make two schema's belonging to SG1 & SG2 respectively.
    // Add a WBCL to SG1.
    // Try assosciating the table of SG2 with the WBCL, it should fail.

    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute(getCreateTestTableSQL());
    try {
      JdbcTestBase.addAsyncEventListenerWithConn("", currentTest,
          "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListener", null,
          new Integer(10), null, null, null, null, null, null, null, null);
      fail("AsyncEventListener attachment should have thrown exception");
    }
    catch (SQLException sqle) {
      if (!"42X01".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }

  //NOTE: The test adds BULKDMLOptimizedDBSynchronizerFilter explicitly on the AsyncEventQueue 
  public void testGatewayEventsSuppressionBehaviour() throws Exception {
    
    // Bug #51835
    if (isTransactional) {
      return;
    }


    // Configure a WBCL
    // Attach it to a table.

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = getConnection(info);
    JdbcTestBase.addAsyncEventListener("SG1", currentTest,
        "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListenerNotify",
        new Integer(10), null, null, null, null, Boolean.FALSE, Boolean.FALSE, null, "true,6");
    AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)Misc
        .getGemFireCache().getAsyncEventQueue(currentTest.toUpperCase());
    //*** Add BulkDMLOptimizedFilter
    asyncQueue.addGatewayEventFilter(WanProcedures.getSerialDBSynchronizerFilter(true));
    JdbcTestBase.startAsyncEventListener(currentTest);
    TestGatewayEventListenerNotify tgen = (TestGatewayEventListenerNotify)this
        .getGatewayListenerForWBCL(currentTest).getAsyncEventListenerForTest();

    Statement stmt = conn.createStatement();
    stmt.execute(getCreateTestTableSQL());

    stmt.execute("Insert into TESTTABLE values(1,'desc1','Add1')");
    stmt.execute("Insert into TESTTABLE values(2,'desc2','Add2')");
    stmt.execute("Insert into TESTTABLE values(3,'desc3','Add3')");
    stmt.execute("Insert into TESTTABLE values(4,'desc4','Add4')");
    stmt.execute("update TESTTABLE set description = 'modified' where id =2");
    stmt.execute("delete from testtable where ADDRESS = 'Add4'");
    synchronized (TestGatewayEventListenerNotify.class) {
      stmt.execute("delete from testtable where ID = 1");
      // Wait for event to arrive in Gateway Queue
      TestGatewayEventListenerNotify.class.wait(30000);
    }
    Event events[] = tgen.getEventsCollected();
    int i = 1;
    for (Event ev : events) {
      assertNotNull(ev);
      switch (i) {
        case 1: {
          assertEquals(ev.getType(), Event.Type.AFTER_INSERT);
          List list = ev.getNewRow();
          assertEquals(list.size(), 3);
          assertEquals(list.get(0), new Integer(1));
          assertEquals(list.get(1), "desc1");
          assertEquals(list.get(2), "Add1");
          assertNull(ev.getModifiedColumns());
          assertEquals(Integer.valueOf(1), ev.getPrimaryKey()[0]);
          break;
        }
        case 2: {
          assertEquals(ev.getType(), Event.Type.AFTER_INSERT);
          List list = ev.getNewRow();
          assertEquals(list.size(), 3);
          assertEquals(list.get(0), new Integer(2));
          assertEquals(list.get(1), "desc2");
          assertEquals(list.get(2), "Add2");
          assertNull(ev.getModifiedColumns());
          assertEquals(Integer.valueOf(2), ev.getPrimaryKey()[0]);
          break;
        }
        case 3: {
          assertEquals(ev.getType(), Event.Type.AFTER_INSERT);
          List list = ev.getNewRow();
          assertEquals(list.size(), 3);
          assertEquals(list.get(0), new Integer(3));
          assertEquals(list.get(1), "desc3");
          assertEquals(list.get(2), "Add3");
          assertNull(ev.getModifiedColumns());
          assertEquals(Integer.valueOf(3), ev.getPrimaryKey()[0]);
          break;
        }
        case 4: {
          assertEquals(ev.getType(), Event.Type.AFTER_INSERT);
          List list = ev.getNewRow();
          assertEquals(list.size(), 3);
          assertEquals(list.get(0), new Integer(4));
          assertEquals(list.get(1), "desc4");
          assertEquals(list.get(2), "Add4");
          assertNull(ev.getModifiedColumns());
          assertEquals(Integer.valueOf(4), ev.getPrimaryKey()[0]);
          break;
        }
        case 5: {
          assertEquals(ev.getType(), Event.Type.AFTER_UPDATE);
          List list = ev.getNewRow();
          assertEquals(list.size(), 3);
          // assertEquals(list.get(0),new Integer(2));
          assertNull(list.get(0));
          assertEquals(list.get(1), "modified");
          // assertEquals(list.get(2),"Add2");
          assertNull(list.get(2));
          int[] modCols = ev.getModifiedColumns();
          assertNotNull(modCols);
          assertNull(ev.getOldRow());
          assertEquals(modCols[0], 2);
          assertEquals(Integer.valueOf(2), ev.getPrimaryKey()[0]);
          break;
        }
        case 6: {
          assertEquals(ev.getType(), Event.Type.AFTER_DELETE);
          List list = ev.getNewRow();
          assertNull(list);
          assertNull(ev.getModifiedColumns());
          // DataValueDescriptor dvd =
          // (DataValueDescriptor)((WBCLEventImpl)ev).getGatewayEvent().getKey();
          // assertNotNull(dvd);
          // assertEquals( dvd.getInt(), 1);
          assertEquals(Integer.valueOf(1), ev.getPrimaryKey()[0]);
          break;

        }
      }
      ++i;
    }
    
    waitForAsyncQueueToDrain("AsyncEventQueue_TESTGATEWAYEVENTSSUPPRESSIONBEHAVIOUR_SERIAL_GATEWAY_SENDER_QUEUE");
  }

  private void dbSynchronizerPersistenceWithoutParams(boolean isPR,
      boolean useGfxdNetclient) throws Exception {

    Statement derbyStmt = null;
    Statement stmt = null;
    final int[] numInserts = new int[] { 0 };
    final int[] numPKUpdates = new int[] { 0 };
    final int[] numPKDeletes = new int[] { 0 };
    final int[] numBulkOp = new int[] { 0 };
    try {
      // The below will spawn a VM with server group set & use name password
      // created
      Properties info = new Properties();
      info.setProperty("server-groups", "SG1");      
      Connection conn = getConnection(info);

      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (currentUserName != null) {
        derbyDbUrl += ("user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      
      String createTable = "create table TESTTABLE (ID int not null primary key , "
          + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int)";
      
      derbyStmt.execute(createTable);

      if (useGfxdNetclient) {
        conn = TestUtil.startNetserverAndGetLocalNetConnection();
      }

      JdbcTestBase.addAsyncEventListenerWithConn("SG1", currentTest,
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer", new Integer(10), null,
          null, null, null, Boolean.FALSE, Boolean.FALSE, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl, conn);
      JdbcTestBase.startAsyncEventListener(currentTest);
      final boolean[] ok = new boolean[] { false };
      stmt = conn.createStatement();
      stmt.execute("create table TESTTABLE (ID int not null primary key , "
          + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
          + "AsyncEventListener (" + currentTest + ')'
          + (isPR ? "" : " replicate")
          + getSQLSuffixClause());
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void afterBulkOpDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps, String dml) {
          numBulkOp[0] += 1;
        }

        @Override
        public void afterPKBasedDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps) {
          if (type.equals(Event.Type.AFTER_DELETE)) {
            numPKDeletes[0] += numRowsModified;
            synchronized (WanTest.this) {
              WanTest.this.notify();
              ok[0] = true;
            }
          }
          else if (type.equals(Event.Type.AFTER_INSERT)) {
            numInserts[0] += numRowsModified;
          }
          else if (type.equals(Event.Type.AFTER_UPDATE)) {
            numPKUpdates[0] += numRowsModified;
          }
        }
      });
      // PK based insert
      stmt.execute("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.execute("Insert into TESTTABLE values(2,'desc2','Add2',2)");
      stmt.execute("Insert into TESTTABLE values(3,'desc3','Add3',3)");
      stmt.execute("Insert into TESTTABLE values(4,'desc4','Add4',4)");
      stmt.execute("Insert into TESTTABLE values(5,'desc5','Add4',5)");
      stmt.execute("Insert into TESTTABLE values(6,'desc6','Add6',6)");
      // Bulk Update
      stmt.execute("update TESTTABLE set ID1 = ID1 +1 ");
      // Bulk delete
      stmt.execute("delete from TESTTABLE where ID1 = 5 ");
      // PK based update
      stmt.execute("update TESTTABLE set description = 'modified' where id =2");
      // PK based Delete
      stmt.execute("delete from testtable where ID = 1");
      conn.commit();
      synchronized (this) {
        if (!ok[0]) {
          this.wait(30000);
        }
      }
      if (conn.getTransactionIsolation() != Connection.TRANSACTION_NONE) {
        // Currently transactional ops go as bulk DMLs and not PK based
        assertEquals(0, numInserts[0]);  
      } else {
        assertEquals(6, numInserts[0]);  
      }
      
      assertEquals(1, numPKUpdates[0]);
      assertEquals(1, numPKDeletes[0]);
      assertEquals(2, numBulkOp[0]);
      // wait for queue to drain
      final AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue(currentTest.toUpperCase());
      final DistributedTestBase.WaitCriterion wc = new DistributedTestBase.WaitCriterion() {
        @Override
        public boolean done() {
          return asyncQueue.getSender().getQueue().size() == 0;
        }

        @Override
        public String description() {
          return "waiting for queue for " + currentTest.toUpperCase()
              + " to drain";
        }
      };
      DistributedTestBase.waitForCriterion(wc, 30000, 500, true);
      JdbcTestBase.stopAsyncEventListener(currentTest);
      validateResults(derbyStmt, stmt, "select * from testtable", false);
    }
    finally {
      if (derbyStmt != null) {
        derbyStmt.execute("drop table TESTTABLE");
      }
      if (stmt != null) {
        stmt.execute("drop table TESTTABLE");
        this.waitTillAllClear();
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      }
      catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  private void dbSynchronizerPersistenceWithParams(boolean isPR)
      throws Exception {
    Statement derbyStmt = null;
    Statement stmt = null;
    final int[] numInserts = new int[] { 0 };
    final int[] numPKUpdates = new int[] { 0 };
    final int[] numPKDeletes = new int[] { 0 };
    final int[] numBulkOp = new int[] { 0 };
    try {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (currentUserName != null) {
        derbyDbUrl += ("user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      derbyStmt.execute("create table TESTTABLE (ID int not null primary key,"
          + "DESCRIPTION varchar(1024), ADDRESS varchar(1024), ID1 int)");
      Properties info = new Properties();
      info.setProperty("server-groups", "SG1");
      Connection conn = getConnection(info);

      JdbcTestBase.addAsyncEventListener("SG1", currentTest,
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer", new Integer(10), null,
          null, null, null, Boolean.FALSE, Boolean.FALSE, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl);
      JdbcTestBase.startAsyncEventListener(currentTest);
      final boolean[] ok = new boolean[] { false };
      stmt = conn.createStatement();
      stmt.execute("create table TESTTABLE (ID int not null primary key, "
          + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int) "
          + "AsyncEventListener (" + currentTest + ')'
          + (isPR ? "" : " replicate")
          + getSQLSuffixClause());
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void afterBulkOpDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps, String dml) {
          numBulkOp[0] += 1;
        }

        @Override
        public void afterPKBasedDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps) {
          if (type.equals(Event.Type.AFTER_DELETE)) {
            numPKDeletes[0] += numRowsModified;
            synchronized (WanTest.this) {
              WanTest.this.notify();
              ok[0] = true;
            }
          }
          else if (type.equals(Event.Type.AFTER_INSERT)) {
            numInserts[0] += numRowsModified;
          }
          else if (type.equals(Event.Type.AFTER_UPDATE)) {
            numPKUpdates[0] += numRowsModified;
          }
        }
      });
      // PK based insert
      stmt.execute("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.execute("Insert into TESTTABLE values(2,'desc2','Add2',2)");
      stmt.execute("Insert into TESTTABLE values(3,'desc3','Add3',3)");
      stmt.execute("Insert into TESTTABLE values(4,'desc4','Add4',4)");
      stmt.execute("Insert into TESTTABLE values(5,'desc5','Add4',5)");
      stmt.execute("Insert into TESTTABLE values(6,'desc6','Add6',6)");
      // Bulk Update
      PreparedStatement pstmt = conn
          .prepareStatement("update TESTTABLE set ID1 = ID1 + ? ");
      pstmt.setInt(1, 1);
      pstmt.executeUpdate();
      // Bulk delete
      pstmt = conn.prepareStatement("delete from TESTTABLE where ID1 = ? ");
      pstmt.setInt(1, 5);
      pstmt.executeUpdate();
      // PK based update
      pstmt = conn
          .prepareStatement("update TESTTABLE set description = ? where id =?");
      pstmt.setString(1, "modified");
      pstmt.setInt(2, 2);
      pstmt.executeUpdate();
      // PK based Delete
      stmt.execute("delete from testtable where ID = 1");
      synchronized (this) {
        if (!ok[0]) {
          this.wait(30000);
        }
      }
      assertEquals(6, numInserts[0]);
      assertEquals(1, numPKUpdates[0]);
      assertEquals(1, numPKDeletes[0]);
      assertEquals(2, numBulkOp[0]);
      // wait for queue to drain
      final AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue(currentTest.toUpperCase());
      final DistributedTestBase.WaitCriterion wc = new DistributedTestBase.WaitCriterion() {
        @Override
        public boolean done() {
          return asyncQueue.getSender().getQueue().size() == 0;
        }

        @Override
        public String description() {
          return "waiting for queue for " + currentTest.toUpperCase()
              + " to drain";
        }
      };
      DistributedTestBase.waitForCriterion(wc, 30000, 500, true);
      JdbcTestBase.stopAsyncEventListener(currentTest);
      validateResults(derbyStmt, stmt, "select * from testtable", false);
    }
    finally {
      if (derbyStmt != null) {
        derbyStmt.execute("drop table TESTTABLE");
      }
      if (stmt != null) {
        stmt.execute("drop table TESTTABLE");
        this.waitTillAllClear();
      }
      /*
       * try { DriverManager.getConnection("jdbc:derby:;shutdown=true");
       * }catch(SQLException sqle) { if(sqle.getMessage().indexOf("shutdown") ==
       * -1) { sqle.printStackTrace(); throw sqle; } }
       */
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  public void testDBSynchronizerPersistenceWithoutParamsForPr()
      throws Exception {

    // Bug #51835
    if (isTransactional) {
      return;
    }

    this.dbSynchronizerPersistenceWithoutParams(true, false /*
                                                             * use gfxd net
                                                             * clien
                                                             */);
  }

  public void testDBSynchronizerPersistenceWithoutParamsForPrUsingGfxdNetClient()
      throws Exception {
    
    // Bug #51835
    if (isTransactional) {
      return;
    }
    
    this.dbSynchronizerPersistenceWithoutParams(true, true /*
                                                            * use gfxd net
                                                            * client
                                                            */);
  }

  public void testDBSynchronizerPersistenceWithoutParamsForReplicate()
      throws Exception {
    // Bug #51835
    if (isTransactional) {
      return;
    }

    this.dbSynchronizerPersistenceWithoutParams(false, true/* use gfxd net clien */);
  }

  public void testDBSynchronizerPersistenceWithParamsForPr() throws Exception {
    // Bug #51835
    if (isTransactional) {
      return;
    }

    this.dbSynchronizerPersistenceWithParams(true);
  }

  public void testDBSynchronizerPersistenceWithParamsForReplicate()
      throws Exception {
    // Bug #51835
    if (isTransactional) {
      return;
    }
    this.dbSynchronizerPersistenceWithParams(false);
  }

  public void testMultipleAsyncEventListeners_Bug43419() throws Exception {
    
    // Bug #51835
    if (isTransactional) {
      return;
    }
    
    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = getConnection(info);
    JdbcTestBase.addAsyncEventListener("SG1", currentTest + "_1",
        "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListenerNotify",
        new Integer(10), null, null, null, null, null, null, null, "test-init-param");

    JdbcTestBase.addAsyncEventListener("SG1", currentTest + "_2",
        "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListenerNotify",
        new Integer(10), null, null, null, null, null, null, null, "test-init-param");
    Statement stmt = conn.createStatement();
    stmt.execute("create table TESTTABLE (ID int not null , "
        + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024)) "
        + "AsyncEventListener (" + currentTest + "_1, " + currentTest + "_2)" 
        + getSQLSuffixClause());
    final Region<?, ?> rgn = Misc.getGemFireCache().getRegion(
        '/' + getCurrentDefaultSchemaName() + "/TESTTABLE");
    assertTrue(rgn.getAttributes().getEnableGateway());
    assertEquals(rgn.getAttributes().getGatewayHubId(), "");

    JdbcTestBase.startAsyncEventListener(currentTest + "_1");
    JdbcTestBase.startAsyncEventListener(currentTest + "_2");
    TestGatewayEventListenerNotify tgen1 = (TestGatewayEventListenerNotify)this
        .getGatewayListenerForWBCL(currentTest + "_1").getAsyncEventListenerForTest();

    TestGatewayEventListenerNotify tgen2 = (TestGatewayEventListenerNotify)this
        .getGatewayListenerForWBCL(currentTest + "_2").getAsyncEventListenerForTest();

    stmt.execute("Insert into TESTTABLE values(1,'desc1','Add1')");

    // Wait for event to arrive in Gateway Queue
    while (true) {
      Event[] events = tgen1.getEventsCollected();
      if (events == null) {
        Thread.sleep(500);
        continue;
      }
      assertEquals(1, events.length);
      break;
    }

    while (true) {
      Event[] events = tgen2.getEventsCollected();
      if (events == null) {
        Thread.sleep(500);
        continue;
      }
      assertEquals(1, events.length);
      break;
    }

    waitForAsyncQueueToDrain("AsyncEventQueue_TESTMULTIPLEASYNCEVENTLISTENERS_BUG43419_1_SERIAL_GATEWAY_SENDER_QUEUE");
    waitForAsyncQueueToDrain("AsyncEventQueue_TESTMULTIPLEASYNCEVENTLISTENERS_BUG43419_2_SERIAL_GATEWAY_SENDER_QUEUE");
  }

  public void testAsyncEventListenerForSkipListenerForReplicate()
      throws Exception {
    asyncEventListenerForSkipListener(true, false, -1);
  }

  public void testAsyncEventListenerForSkipListenerForPR() throws Exception {
    asyncEventListenerForSkipListener(false, false, -1);
  }

  public void testAsyncEventListenerForSkipListenerForPRUsingNetConnection()
      throws Exception {
    asyncEventListenerForSkipListener(false, true,
        AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET));
  }

  public void testAsyncEventListenerForSkipListenerForReplicateUsingNetConnection()
      throws Exception {
    asyncEventListenerForSkipListener(true, true,
        AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET));
  }

  private void asyncEventListenerForSkipListener(boolean useReplicate,
      boolean useNetConnection, int port) throws Exception {

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = null;
    try {
      conn = TestUtil.getConnection(info);
      info.clear();
      info.setProperty(com.pivotal.gemfirexd.Attribute.SKIP_LISTENERS, "true");
      if (useNetConnection) {
        TestUtil.startNetServer(port, null);
      }

      JdbcTestBase.addAsyncEventListener("SG1", currentTest,
          "com.pivotal.gemfirexd.jdbc.WanTest$TestGatewayEventListenerNotify",
          new Integer(10), null, null, null, null, null, null, null,
          "test-init-param,0");

      Statement stmt = conn.createStatement();
      stmt.execute("create table TESTTABLE (ID int not null primary key, "
          + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024)) "
          + "AsyncEventListener (" + currentTest + ')'
          + (useReplicate ? "replicate" : "")
          + getSQLSuffixClause());
      JdbcTestBase.startAsyncEventListener(currentTest);
      conn = useNetConnection ? TestUtil.getNetConnection(port, null, info)
          : TestUtil.getConnection(info);
      stmt = conn.createStatement();
      TestGatewayEventListenerNotify tgen1 = (TestGatewayEventListenerNotify)this
          .getGatewayListenerForWBCL(currentTest).getAsyncEventListenerForTest();
      // Add pk based insert
      stmt.execute("Insert into TESTTABLE values(1,'desc1','Add1')");
      stmt.execute("Insert into TESTTABLE values(2,'desc2','Add2')");
      // bulk update
      stmt.execute("Update TESTTABLE set DESCRIPTION =''");
      // PK update
      stmt.execute("Update TESTTABLE set DESCRIPTION ='desc1' where ID = 1");

      // PK delete
      stmt.execute("delete from TESTTABLE where ID = 1");
      // bulk delete
      stmt.execute("delete from TESTTABLE ");
      Thread.sleep(3000);
      Event[] events = tgen1.getEventsCollected();
      assertEquals(0, events.length);
      assertEquals(0, tgen1.getNumCallbacks());
      assertFalse(tgen1.exceptionOccurred());
    }
    finally {
      conn.close();
      if (useNetConnection) {
        TestUtil.stopNetServer();
      }
    }

  }

  public void testDBSynchronizerForSkipListenerReplicate() throws Exception {
    dbSynchronizerForSkipListener(true);
  }

  public void testDBSynchronizerForSkipListenerPR() throws Exception {
    dbSynchronizerForSkipListener(false);
  }

  private void dbSynchronizerForSkipListener(boolean useReplicate)
      throws Exception {
    Statement derbyStmt = null;
    Statement stmt = null;
    final boolean[] anyCallBack = new boolean[] { false };
    try {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      if (currentUserName != null) {
        derbyDbUrl += ("user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      derbyStmt.execute("create table TESTTABLE (ID int not null primary key,"
          + "DESCRIPTION varchar(1024), ADDRESS varchar(1024), ID1 int)");
      Properties info = new Properties();
      info.setProperty("server-groups", "SG1");
      info.setProperty(com.pivotal.gemfirexd.Attribute.SKIP_LISTENERS, "true");
      Connection conn = getConnection(info);

      JdbcTestBase.addAsyncEventListener("SG1", currentTest,
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer", new Integer(10), null,
          null, null, null, Boolean.FALSE, Boolean.FALSE, null,
          "org.apache.derby.jdbc.EmbeddedDriver," + derbyDbUrl);
      JdbcTestBase.startAsyncEventListener(currentTest);
      stmt = conn.createStatement();
      stmt.execute("create table TESTTABLE (ID int not null primary key, "
          + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int) "
          + "AsyncEventListener (" + currentTest + ") "
          + (useReplicate ? "replicate " : "")
          + getSQLSuffixClause());
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public void afterBulkOpDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps, String dml) {
          anyCallBack[0] = true;
        }

        @Override
        public void afterPKBasedDBSynchExecution(Event.Type type,
            int numRowsModified, Statement ps) {
          anyCallBack[0] = true;
        }
      });
      // PK based insert
      stmt.execute("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.execute("Insert into TESTTABLE values(2,'desc2','Add2',2)");
      stmt.execute("Insert into TESTTABLE values(3,'desc3','Add3',3)");
      stmt.execute("Insert into TESTTABLE values(4,'desc4','Add4',4)");
      stmt.execute("Insert into TESTTABLE values(5,'desc5','Add4',5)");
      stmt.execute("Insert into TESTTABLE values(6,'desc6','Add6',6)");
      // Bulk Update
      PreparedStatement pstmt = conn
          .prepareStatement("update TESTTABLE set ID1 = ID1 + ? ");
      pstmt.setInt(1, 1);
      pstmt.executeUpdate();
      // Bulk delete
      pstmt = conn.prepareStatement("delete from TESTTABLE where ID1 = ? ");
      pstmt.setInt(1, 5);
      pstmt.executeUpdate();
      // PK based update
      pstmt = conn
          .prepareStatement("update TESTTABLE set description = ? where id =?");
      pstmt.setString(1, "modified");
      pstmt.setInt(2, 2);
      pstmt.executeUpdate();
      // PK based Delete
      stmt.execute("delete from testtable where ID = 1");
      Thread.sleep(3000);
      JdbcTestBase.stopAsyncEventListener(currentTest);
      ResultSet derbyRs = derbyStmt.executeQuery("select * from testtable");

      assertFalse(derbyRs.next());
      assertFalse(anyCallBack[0]);
      derbyRs.close();
    }
    finally {
      if (derbyStmt != null) {
        derbyStmt.execute("drop table TESTTABLE");
      }
      if (stmt != null) {
        stmt.execute("drop table TESTTABLE");
        this.waitTillAllClear();
      }

      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testExternalProc() throws Exception {
    Connection conn = getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE PROCEDURE PRO_TRIGGER(IN num INTEGER ) "
        + "PARAMETER STYLE JAVA  LANGUAGE JAVA EXTERNAL NAME "
        + "'com.pivotal.gemfirexd.jdbc.WanTest.myTest(java.lang.Integer)'");
    conn = getConnection();
    CallableStatement cs = conn.prepareCall("CALL PRO_TRIGGER(?)");
    // cs.setInt(1,5);
    cs.setNull(1, Types.INTEGER);
    cs.execute();
    stmt.execute("CALL PRO_TRIGGER(1) ");
  }

  public void testGatewaySenderToSySTables() throws SQLException {
    getConnectionWithServerGroup();

    getConnection()
        .createStatement()
        .execute(
            "CREATE GATEWAYSENDER mySender ( remotedsid 2 socketbuffersize 1000 "
                + "manualstart true  SOCKETREADTIMEOUT 1000 ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false DISKSYNCHRONOUS false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100) server groups (sg1)");

    ResultSet hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.GATEWAYSENDERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(rsmd.getColumnCount(), 15);
    while (hubRs.next()) {
      assertEquals("SENDER_ID", rsmd.getColumnName(1));
      assertEquals("REMOTE_DS_ID", rsmd.getColumnName(2));
      assertEquals("SERVER_GROUPS", rsmd.getColumnName(3));
      assertEquals("SOCKET_BUFFER_SIZE", rsmd.getColumnName(4));
      assertEquals("MANUAL_START", rsmd.getColumnName(5));
      assertEquals("SOCKET_READ_TIMEOUT", rsmd.getColumnName(6));
      assertEquals("BATCH_CONFLATION", rsmd.getColumnName(7));
      assertEquals("BATCH_SIZE", rsmd.getColumnName(8));
      assertEquals("BATCH_TIME_INTERVAL", rsmd.getColumnName(9));
      assertEquals("IS_PERSISTENCE", rsmd.getColumnName(10));
      assertEquals("DISK_STORE_NAME", rsmd.getColumnName(11));
      assertEquals("MAX_QUEUE_MEMORY", rsmd.getColumnName(12));
      assertEquals("ALERT_THRESHOLD", rsmd.getColumnName(13));
      assertEquals("IS_STARTED", rsmd.getColumnName(14));
      assertEquals("DISK_SYNCHRONOUS", rsmd.getColumnName(15));
      assertEquals("MYSENDER", hubRs.getString(1));
      assertEquals(2, hubRs.getInt(2));
      assertEquals("SG1", hubRs.getString(3));
      assertEquals(1000, hubRs.getInt(4));
      assertEquals(true, hubRs.getBoolean(5));
      assertEquals(1000, hubRs.getInt(6));
      assertEquals(true, hubRs.getBoolean(7));
      assertEquals(10, hubRs.getInt(8));
      assertEquals(100, hubRs.getInt(9));
      assertEquals(false, hubRs.getBoolean(10));
      assertEquals(null, hubRs.getString(11));
      assertEquals(400, hubRs.getInt(12));
      assertEquals(100, hubRs.getInt(13));
      assertEquals(false, hubRs.getBoolean(14));
      assertEquals(false, hubRs.getBoolean(15));
    }

  }

  public void testGatewaySenderDefaultsToSySTables() throws SQLException {
    getConnectionWithServerGroup();

    getConnection()
        .createStatement()
        .execute(
            "CREATE GATEWAYSENDER mySender ( remotedsid 2 manualstart true) server groups (sg1)");

    ResultSet hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.GATEWAYSENDERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(rsmd.getColumnCount(), 15);
    while (hubRs.next()) {
      assertEquals("MYSENDER", hubRs.getString(1));
      assertEquals(2, hubRs.getInt(2));
      assertEquals("SG1", hubRs.getString(3));
      assertEquals(GatewaySender.DEFAULT_SOCKET_BUFFER_SIZE, hubRs.getInt(4));
      // assertEquals("primary", hubRs.getString(5)); // no longer present
      assertEquals(true, hubRs.getBoolean(5));
      assertEquals(GatewaySender.DEFAULT_SOCKET_READ_TIMEOUT, hubRs.getInt(6));
      assertEquals(GatewaySender.DEFAULT_BATCH_CONFLATION, hubRs.getBoolean(7));
      assertEquals(GatewaySender.DEFAULT_BATCH_SIZE, hubRs.getInt(8));
      assertEquals(GatewaySender.DEFAULT_BATCH_TIME_INTERVAL, hubRs.getInt(9));
      assertEquals(false, hubRs.getBoolean(10));
      assertEquals(null, hubRs.getString(11));
      assertEquals(GatewaySender.DEFAULT_MAXIMUM_QUEUE_MEMORY, hubRs.getInt(12));
      assertEquals(GatewaySender.DEFAULT_ALERT_THRESHOLD, hubRs.getInt(13));
      assertEquals(false, hubRs.getBoolean(14));
      assertEquals(false, hubRs.getBoolean(15));
    }
  }

  public void testAsyncEventListenersToSySTables() throws Exception {
    getConnectionWithServerGroup();
    Connection conn = getConnection();

    conn.createStatement()
        .execute(
            "CREATE ASYNCEVENTLISTENER myListener ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true'"
                + " manualstart false  ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false DISKSYNCHRONOUS false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100) server groups (sg1)");

    ResultSet hubRs = conn.createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      assertEquals("ID", rsmd.getColumnName(1));
      assertEquals("LISTENER_CLASS", rsmd.getColumnName(2));
      assertEquals("SERVER_GROUPS", rsmd.getColumnName(3));
      assertEquals("MANUAL_START", rsmd.getColumnName(4));
      assertEquals("BATCH_CONFLATION", rsmd.getColumnName(5));
      assertEquals("BATCH_SIZE", rsmd.getColumnName(6));
      assertEquals("BATCH_TIME_INTERVAL", rsmd.getColumnName(7));
      assertEquals("IS_PERSISTENCE", rsmd.getColumnName(8));
      assertEquals("DISK_STORE_NAME", rsmd.getColumnName(9));
      assertEquals("MAX_QUEUE_MEMORY", rsmd.getColumnName(10));
      assertEquals("ALERT_THRESHOLD", rsmd.getColumnName(11));
      assertEquals("IS_STARTED", rsmd.getColumnName(12));
      assertEquals("DISK_SYNCHRONOUS", rsmd.getColumnName(14));
      assertEquals("MYLISTENER", hubRs.getString(1));
      assertEquals("com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          hubRs.getString(2));
      assertEquals("SG1", hubRs.getString(3));
      assertEquals(false, hubRs.getBoolean(4));
      assertEquals(true, hubRs.getBoolean(5));
      assertEquals(10, hubRs.getInt(6));
      assertEquals(100, hubRs.getInt(7));
      assertEquals(false, hubRs.getBoolean(8));
      assertEquals(null, hubRs.getString(9));
      assertEquals(400, hubRs.getInt(10));
      assertEquals(100, hubRs.getInt(11));
      assertEquals(true, hubRs.getBoolean(12));
      assertEquals(false, hubRs.getBoolean(14));
    }
  }

  public void testAsyncEventListenersToSySTables_NetClient() throws Exception {
    getConnectionWithServerGroup();
    Connection conn = TestUtil.startNetserverAndGetLocalNetConnection();
    conn.createStatement()
        .execute(
            "CREATE ASYNCEVENTLISTENER myListener ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true'"
                + " manualstart false  ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false DISKSYNCHRONOUS false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100) server groups (sg1)");

    ResultSet hubRs = conn.createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      assertEquals("ID", rsmd.getColumnName(1));
      assertEquals("LISTENER_CLASS", rsmd.getColumnName(2));
      assertEquals("SERVER_GROUPS", rsmd.getColumnName(3));
      assertEquals("MANUAL_START", rsmd.getColumnName(4));
      assertEquals("BATCH_CONFLATION", rsmd.getColumnName(5));
      assertEquals("BATCH_SIZE", rsmd.getColumnName(6));
      assertEquals("BATCH_TIME_INTERVAL", rsmd.getColumnName(7));
      assertEquals("IS_PERSISTENCE", rsmd.getColumnName(8));
      assertEquals("DISK_STORE_NAME", rsmd.getColumnName(9));
      assertEquals("MAX_QUEUE_MEMORY", rsmd.getColumnName(10));
      assertEquals("ALERT_THRESHOLD", rsmd.getColumnName(11));
      assertEquals("IS_STARTED", rsmd.getColumnName(12));
      assertEquals("DISK_SYNCHRONOUS", rsmd.getColumnName(14));      
      assertEquals("MYLISTENER", hubRs.getString(1));
      assertEquals("com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          hubRs.getString(2));
      assertEquals("SG1", hubRs.getString(3));
      assertEquals(false, hubRs.getBoolean(4));
      assertEquals(true, hubRs.getBoolean(5));
      assertEquals(10, hubRs.getInt(6));
      assertEquals(100, hubRs.getInt(7));
      assertEquals(false, hubRs.getBoolean(8));
      assertEquals(null, hubRs.getString(9));
      assertEquals(400, hubRs.getInt(10));
      assertEquals(100, hubRs.getInt(11));
      assertEquals(true, hubRs.getBoolean(12));
      assertEquals(false, hubRs.getBoolean(14));
    }
  }

  public void testAsyncEventListenersDefaultsToSySTables() throws SQLException {
    getConnectionWithServerGroup();

    getConnection()
        .createStatement()
        .execute(
            "CREATE ASYNCEVENTLISTENER myListener ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true' manualstart true) server groups (sg1)");

    ResultSet hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, false);
    }
    CallableStatement cs = getConnection().prepareCall(
        "call SYS.START_ASYNC_EVENT_LISTENER (?)");
    cs.setString(1, "MYLISTENER");
    cs.execute();

    hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, true);
    }

    cs = getConnection().prepareCall("call SYS.STOP_ASYNC_EVENT_LISTENER (?)");
    cs.setString(1, "MYLISTENER");
    cs.execute();

    hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, false);
    }
    getConnection().createStatement().execute(
        "DROP ASYNCEVENTLISTENER MYLISTENER");

    hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    assertFalse(hubRs.next());

    getConnection()
        .createStatement()
        .execute(
            "CREATE ASYNCEVENTLISTENER myListener ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true' manualstart true) server groups (sg1)");

    hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, false);
    }
    cs = getConnection().prepareCall("call SYS.START_ASYNC_EVENT_LISTENER (?)");
    cs.setString(1, "MYLISTENER");
    cs.execute();

    hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, true);
    }

    cs = getConnection().prepareCall("call SYS.STOP_ASYNC_EVENT_LISTENER (?)");
    cs.setString(1, "MYLISTENER");
    cs.execute();

    hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, false);
    }

  }

  public void validateSysTableRows(ResultSet hubRs, boolean isStarted)
      throws SQLException {
    assertEquals("MYLISTENER", hubRs.getString(1));
    assertEquals("com.pivotal.gemfirexd.callbacks.DBSynchronizer",
        hubRs.getString(2));
    assertEquals("SG1", hubRs.getString(3));
    assertEquals(true, hubRs.getBoolean(4));
    assertEquals(GatewaySender.DEFAULT_BATCH_CONFLATION, hubRs.getBoolean(5));
    assertEquals(GatewaySender.DEFAULT_BATCH_SIZE, hubRs.getInt(6));
    assertEquals(AsyncEventQueueFactoryImpl.DEFAULT_BATCH_TIME_INTERVAL,
        hubRs.getInt(7));
    assertEquals(false, hubRs.getBoolean(8));
    assertEquals(null, hubRs.getString(9));
    assertEquals(GatewaySender.DEFAULT_MAXIMUM_QUEUE_MEMORY, hubRs.getInt(10));
    assertEquals(GatewaySender.DEFAULT_ALERT_THRESHOLD, hubRs.getInt(11));
    assertEquals(isStarted, hubRs.getBoolean(12));
    // in gemxd default is false.
    assertEquals(false, hubRs.getBoolean(14));
  }

  public void testAsyncEventListenersDefaultsToSySTables_NetClient()
      throws Exception {
    getConnectionWithServerGroup();
    Connection conn = TestUtil.startNetserverAndGetLocalNetConnection();

    conn.createStatement()
        .execute(
            "CREATE ASYNCEVENTLISTENER myListener ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true' manualstart true) server groups (sg1)");

    ResultSet hubRs = conn.createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, false);
    }
    CallableStatement cs = conn
        .prepareCall("call SYS.START_ASYNC_EVENT_LISTENER (?)");
    cs.setString(1, "MYLISTENER");
    cs.execute();

    hubRs = conn.createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, true);
    }

    cs = conn.prepareCall("call SYS.STOP_ASYNC_EVENT_LISTENER (?)");
    cs.setString(1, "MYLISTENER");
    cs.execute();

    hubRs = conn.createStatement().executeQuery(
        "select * from SYS.ASYNCEVENTLISTENERS");
    rsmd = hubRs.getMetaData();
    assertEquals(14, rsmd.getColumnCount());
    while (hubRs.next()) {
      validateSysTableRows(hubRs, false);
    }

  }

  public void testGatewayReceiversDefaults() throws Exception {

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1,SG2,PQR");    
    Connection con = getConnection(info);
    con.createStatement().execute("CREATE GATEWAYRECEIVER ok ");
    ResultSet hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(9, rsmd.getColumnCount());
    int runningPort = 0;
    while (hubRs.next()) {
      assertEquals("ID", rsmd.getColumnName(1));
      assertEquals("RUNNING_PORT", rsmd.getColumnName(2));
      assertEquals("START_PORT", rsmd.getColumnName(3));
      assertEquals("END_PORT", rsmd.getColumnName(4));
      assertEquals("SERVER_GROUPS", rsmd.getColumnName(5));
      assertEquals("SOCKET_BUFFER_SIZE", rsmd.getColumnName(6));
      assertEquals("MAX_TIME_BETWEEN_PINGS", rsmd.getColumnName(7));
      assertEquals("BIND_ADDRESS", rsmd.getColumnName(8));
      assertEquals("HOST_NAME_FOR_SENDERS", rsmd.getColumnName(9));
      assertEquals("OK", hubRs.getString(1));
      runningPort = hubRs.getInt(2);
      assertTrue((GatewayReceiver.DEFAULT_START_PORT <= runningPort)
          && (GatewayReceiver.DEFAULT_END_PORT > runningPort));
      assertEquals(GatewayReceiver.DEFAULT_START_PORT, hubRs.getInt(3));
      assertEquals(GatewayReceiver.DEFAULT_END_PORT, hubRs.getInt(4));
      assertEquals("", hubRs.getString(5));
      assertEquals(GatewayReceiver.DEFAULT_SOCKET_BUFFER_SIZE, hubRs.getInt(6));
      assertEquals(GatewayReceiver.DEFAULT_MAXIMUM_TIME_BETWEEN_PINGS,
          hubRs.getInt(7));
      assertEquals(GatewayReceiver.DEFAULT_BIND_ADDRESS, hubRs.getString(8));
      assertEquals(SocketCreator.getLocalHost().getHostName(), hubRs.getString(9));
    }
  }
  
  public void testGatewayReceiversHNSWithCustomProperty() throws Exception {

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1,SG2,PQR");
    info.setProperty("custom-NIC1", "NIC1");
    Connection con = getConnection(info);
    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER ok (hostnameforsenders 'NIC1')");
    ResultSet hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(9, rsmd.getColumnCount());
    while (hubRs.next()) {
      assertEquals("HOST_NAME_FOR_SENDERS", rsmd.getColumnName(9));
      assertEquals("NIC1", hubRs.getString(9));
    }
  }

  public void testGatewayReceiversHNSWithIP() throws Exception {

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1,SG2,PQR");
    info.setProperty("custom-NIC1", "NIC1");
    Connection con = getConnection(info);
    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER ok (hostnameforsenders '127.0.0.1')");
    ResultSet hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(9, rsmd.getColumnCount());
    while (hubRs.next()) {
      assertEquals("HOST_NAME_FOR_SENDERS", rsmd.getColumnName(9));
      assertEquals("127.0.0.1", hubRs.getString(9));
    }
  }

  public void testGatewayReceiversHNSWithLocalHost() throws Exception {

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1,SG2,PQR");
    info.setProperty("custom-NIC1", "NIC1");
    Connection con = getConnection(info);
    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER ok (hostnameforsenders 'localhost')");
    ResultSet hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(9, rsmd.getColumnCount());
    while (hubRs.next()) {
      assertEquals("HOST_NAME_FOR_SENDERS", rsmd.getColumnName(9));
      assertEquals("localhost", hubRs.getString(9));
    }
  }

  public void testGatewayReceiversHNSWithInvalidProperty() throws Exception {

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1,SG2,PQR");
    Connection con = getConnection(info);
    try {
      con.createStatement().execute(
          "CREATE GATEWAYRECEIVER ok (hostnameforsenders 'NIC1')");
      fail("This test should have thrown exception");
    } catch (Exception e) {
      return;
    }
  }

  public void testGatewayReceivers() throws Exception {

    Properties info = new Properties();
    info.setProperty("server-groups", "SG1,SG2,PQR");
    Connection con = getConnection(info);
    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER ok ( socketbuffersize 1000 "
            + "bindaddress '127.0.0.1' maxtimebetweenpings 10000) "
            + "server groups (sg1)");
    ResultSet hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS");
    ResultSetMetaData rsmd = hubRs.getMetaData();
    assertEquals(9, rsmd.getColumnCount());
    int runningPort = 0;
    while (hubRs.next()) {
      assertEquals("ID", rsmd.getColumnName(1));
      assertEquals("RUNNING_PORT", rsmd.getColumnName(2));
      assertEquals("START_PORT", rsmd.getColumnName(3));
      assertEquals("END_PORT", rsmd.getColumnName(4));
      assertEquals("SERVER_GROUPS", rsmd.getColumnName(5));
      assertEquals("SOCKET_BUFFER_SIZE", rsmd.getColumnName(6));
      assertEquals("MAX_TIME_BETWEEN_PINGS", rsmd.getColumnName(7));
      assertEquals("BIND_ADDRESS", rsmd.getColumnName(8));
      assertEquals("OK", hubRs.getString(1));
      runningPort = hubRs.getInt(2);
      assertTrue("unexpected running port " + runningPort,
          (GatewayReceiver.DEFAULT_START_PORT <= runningPort)
              && (GatewayReceiver.DEFAULT_END_PORT > runningPort));
      assertEquals(GatewayReceiver.DEFAULT_START_PORT, hubRs.getInt(3));
      assertEquals(GatewayReceiver.DEFAULT_END_PORT, hubRs.getInt(4));
      assertEquals("SG1", hubRs.getString(5));
      assertEquals(1000, hubRs.getInt(6));
      assertEquals(10000, hubRs.getInt(7));
      assertEquals("127.0.0.1", hubRs.getString(8));
    }

    hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS WHERE SERVER_GROUPS "
            + "LIKE '%SG1%'");
    assertEquals(9, rsmd.getColumnCount());
    while (hubRs.next()) {
      assertEquals("SG1", hubRs.getString(5));
    }
    con.createStatement().execute("DROP GATEWAYRECEIVER OK");
    hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS");
    rsmd = hubRs.getMetaData();
    while (hubRs.next()) {
      fail("There should not be any row present in the "
          + "SYS.GATEWAYRECEIVERS table");
    }
    int startPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    int endPort = startPort + 3;
    String portRange = "startport " + startPort + " endport " + endPort + ' ';
    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER R1 (" + portRange
            + "socketbuffersize 1000 bindaddress '0.0.0.0'"
            + " maxtimebetweenpings 10000) server groups (sg1)");

    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER R2 (" + portRange
            + "socketbuffersize 1000 bindaddress '0.0.0.0' "
            + "maxtimebetweenpings 10000) server groups (sg1)");
    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER R3 (" + portRange
            + "socketbuffersize 1000 bindaddress '0.0.0.0' "
            + "maxtimebetweenpings 10000) server groups (sg1)");
    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER R4 (" + portRange
            + "socketbuffersize 1000 bindaddress '0.0.0.0' "
            + "maxtimebetweenpings 10000) server groups (sg1)");
    hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS");
    rsmd = hubRs.getMetaData();
    while (hubRs.next()) {
      runningPort = hubRs.getInt(2);
      assertTrue(Integer.toString(runningPort), (startPort <= runningPort)
          && (endPort >= runningPort));
      assertEquals(startPort, hubRs.getInt(3));
      assertEquals(endPort, hubRs.getInt(4));
      assertEquals("SG1", hubRs.getString(5));
      assertEquals(1000, hubRs.getInt(6));
      assertEquals(10000, hubRs.getInt(7));
      assertEquals("0.0.0.0", hubRs.getString(8));
    }
    try {
      con.createStatement().execute(
          "CREATE GATEWAYRECEIVER R4 (" + portRange
              + "socketbuffersize 1000 bindaddress '0.0.0.0' "
              + "maxtimebetweenpings 10000) server groups (sg1)");
      fail("Test was expected to throw BindException ");
    }
    catch (Exception e) {
      if (e instanceof SQLException) {
          return;
      }
      fail("Test was expected to throw SQLException ");
    }
    con.createStatement().execute(
        "CREATE GATEWAYRECEIVER R5 (startport " + startPort + " endport "
            + (endPort + 1) + " socketbuffersize 1000 bindaddress '0.0.0.0' "
            + "maxtimebetweenpings 10000) server groups (sg1)");

    for (GatewayReceiver rcvr : Misc.getGemFireCache().getGatewayReceivers()) {
      con.createStatement().execute("DROP GATEWAYRECEIVER " + rcvr.getId());
    }
    hubRs = con.createStatement().executeQuery(
        "select * from SYS.GATEWAYRECEIVERS");
    rsmd = hubRs.getMetaData();
    while (hubRs.next()) {
      fail("There should not be any row present in the "
          + "SYS.GATEWAYRECEIVERS table");
    }
  }

  public void testBug45823() throws SQLException {
    Properties info = new Properties();
    Connection conn = getConnection(info);
    Statement stmt = conn.createStatement();
    try {
      // Negative port numbers are not allowed - syntax error
      stmt.execute("create gatewayreceiver mybad1 (startport -5 endport 5)");
      fail("CREATE GATEWAYRECEIVER should have failed with illegal numerical port value");
    }
    catch (SQLException sqle) {
      if (!"42X44".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      // End port must be greater than start port - semantic error
      stmt.execute("create gatewayreceiver mybad1 (startport 10 endport 5)");
      fail("CREATE GATEWAYRECEIVER should have failed with illegal port combination");
    }
    catch (SQLException sqle) {
      if (!"22008".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      // Port numbers cannot be > 65535 - semantic error
      stmt.execute("create gatewayreceiver mybad1 (startport 10 endport 99999)");
      fail("CREATE GATEWAYRECEIVER should have failed with illegal port combination");
    }
    catch (SQLException sqle) {
      if (!"22008".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }

  public void testBug45822() throws SQLException {
    Properties info = new Properties();
    Connection conn = getConnection(info);
    Statement stmt = conn.createStatement();
    // Drop a nonexistent async event listener, gateway receiver,
    //  gatewaysender, should all get sqlstate
    //  42Y55 (object not found)
    try {
      stmt.execute("DROP ASYNCEVENTLISTENER NOSUCH1");
      fail("DROP ASYNCEVENTLISTENER NOSUCH1 should have failed");
    }
    catch (SQLException sqle) {
      if (!"42Y55".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("DROP GATEWAYRECEIVER NOSUCH1");
      fail("DROP GATEWAYRECEIVER NOSUCH1 should have failed");
    }
    catch (SQLException sqle) {
      if (!"42Y55".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
    try {
      stmt.execute("DROP GATEWAYSENDER NOSUCH1");
      fail("DROP GATEWAYSENDER NOSUCH1 should have failed");
    }
    catch (SQLException sqle) {
      if (!"42Y55".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
  }

  public void testAlterTableWithAsyncEventListener() throws SQLException {
    getConnectionWithServerGroup();
    getConnection().createStatement().execute(
        "CREATE TABLE TESTTABLE (ID INT NOT NULL)" + getSQLSuffixClause());
    getConnection().createStatement().execute(
        "CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
            + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
            + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
            + "jdbc:derby:newDB;create=true' "
            + "manualstart false) server groups (sg1)");
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER (myListener) ");
    AbstractRegion r = (AbstractRegion)Misc.getGemFireCache().getRegion(
        "/APP/TESTTABLE");
    Assert.assertEquals(1, r.getAsyncEventQueueIds().size());
    Assert.assertTrue(r.getAsyncEventQueueIds().contains("MYLISTENER"));
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET EVICTION MAXSIZE 10");
    Assert.assertEquals(1, r.getAsyncEventQueueIds().size());
    Assert.assertTrue(r.getAsyncEventQueueIds().contains("MYLISTENER"));
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER () ");
    Assert.assertEquals(0, r.getAsyncEventQueueIds().size());
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET EVICTION MAXSIZE 10");

    getConnection().createStatement().execute("CREATE ASYNCEVENTLISTENER "
        + "myListener2 ( listenerclass "
        + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' initparams "
        + "'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true' "
        + "manualstart false) server groups (sg1)");
    getConnection().createStatement().execute("ALTER TABLE "
        + "TESTTABLE SET ASYNCEVENTLISTENER (myListener, myListener2) ");
    Assert.assertEquals(2, r.getAsyncEventQueueIds().size());
    Assert.assertTrue(r.getAsyncEventQueueIds().contains("MYLISTENER"));
    Assert.assertTrue(r.getAsyncEventQueueIds().contains("MYLISTENER2"));
    ResultSet hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.SYSTABLES where tablename='TESTTABLE'");
    while (hubRs.next()) {
      Assert.assertEquals("MYLISTENER,MYLISTENER2", hubRs.getString(17));
    }
  }

  public void testAlterGatewaySendersAndAsyncEventListener()
      throws SQLException {
    getConnectionWithServerGroup();

    getConnection()
        .createStatement()
        .execute(
            "CREATE GATEWAYSENDER mySender ( remotedsid 2 socketbuffersize 1000 "
                + "manualstart true  SOCKETREADTIMEOUT 1000 ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100) server groups (sg1)");

    getConnection().createStatement().execute(
        "CREATE TABLE TESTTABLE (ID INT NOT NULL) GATEWAYSENDER(mySender)" + getSQLSuffixClause());

    getConnection().createStatement().execute(
        "CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
            + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
            + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
            + "jdbc:derby:newDB;create=true' "
            + "manualstart false) server groups (sg1)");

    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER (myListener) ");

    ResultSet hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.SYSTABLES where tablename='TESTTABLE'");
    while (hubRs.next()) {
      Assert.assertEquals("MYLISTENER", hubRs.getString(17));
      Assert.assertEquals("MYSENDER", hubRs.getString(19));
    }
    
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET GATEWAYSENDER () ");

    hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.SYSTABLES where tablename='TESTTABLE'");
    while (hubRs.next()) {
      Assert.assertEquals("MYLISTENER", hubRs.getString(17));
      Assert.assertEquals(null, hubRs.getString(19));
    }
  }

  public void _testGatewayProceduresException() throws SQLException {
    getConnectionWithServerGroup();

    getConnection()
        .createStatement()
        .execute(
            "CREATE TABLE TESTTABLE (ID INT NOT NULL) ASYNCEVENTLISTENER(myListener)");
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET GATEWAYSENDER(mySender)");
    try {
      getConnection().createStatement().execute(
          "CALL SYS.START_GATEWAYSENDER('mySender')");
      fail();
    } catch (SQLException ex) {
      if (!(ex.getSQLState().equals("X0Z23") && ex.getErrorCode() == 20000)) {
        fail();
      }
    }
    try {
      getConnection().createStatement().execute(
          "CALL SYS.STOP_GATEWAYSENDER('mySender')");
      fail();
    } catch (SQLException ex) {
      if (!(ex.getSQLState().equals("X0Z23") && ex.getErrorCode() == 20000)) {
        fail();
      }
    }
    try {
      getConnection().createStatement().execute(
          "CALL SYS.START_ASYNC_EVENT_LISTENER('myListener')");
      fail();
    } catch (SQLException ex) {
      if (!(ex.getSQLState().equals("X0Z23") && ex.getErrorCode() == 20000)) {
        fail();
      }
    }
    try {
      getConnection().createStatement().execute(
          "CALL SYS.STOP_ASYNC_EVENT_LISTENER('myListener')");
      fail();
    } catch (SQLException ex) {
      if (!(ex.getSQLState().equals("X0Z23") && ex.getErrorCode() == 20000)) {
        fail();
      }
    }
    ResultSet hubRs = getConnection().createStatement().executeQuery(
        "select * from SYS.SYSTABLES where tablename='TESTTABLE'");
    while (hubRs.next()) {
      Assert.assertEquals("MYLISTENER", hubRs.getString(17));
      Assert.assertEquals("MYSENDER", hubRs.getString(19));
    }

    getConnection().createStatement().execute(
        "CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
            + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
            + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
            + "jdbc:derby:newDB;create=true' "
            + "manualstart false) server groups (sg1)");

    getConnection().createStatement().execute(
        "CALL SYS.START_ASYNC_EVENT_LISTENER('myListener')");

    getConnection().createStatement().execute(
        "CALL SYS.STOP_ASYNC_EVENT_LISTENER('myListener')");

    getConnection()
        .createStatement()
        .execute(
            "CREATE GATEWAYSENDER mySender ( remotedsid 2 socketbuffersize 1000 "
                + "manualstart true  SOCKETREADTIMEOUT 1000 ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100) server groups (sg1)");

    try {
      getConnection().createStatement().execute(
          "CALL SYS.START_GATEWAYSENDER('mySender')");
    } catch (SQLException e) {
      if (!(e.getCause() instanceof GatewaySenderConfigurationException)) {
        fail();
      }
    }

    getConnection().createStatement().execute(
        "CALL SYS.STOP_GATEWAYSENDER('mySender')");
  }
  
  public void testNoLocalGatewayAsyncEventListenerWarning() throws SQLException {
    getConnectionWithServerGroup();

    getConnection().createStatement().execute(
        "CREATE TABLE TESTTABLE (ID INT NOT NULL) GATEWAYSENDER(mySender)" + getSQLSuffixClause());
    getConnection().createStatement().execute("DROP TABLE TESTTABLE");
    this.waitTillAllClear();
    getConnection()
        .createStatement()
        .execute(
            "CREATE TABLE TESTTABLE (ID INT NOT NULL) ASYNCEVENTLISTENER(myListener)" + getSQLSuffixClause());
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER(myListener)");
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET GATEWAYSENDER(mySender)");
    getConnection().createStatement().execute("DROP TABLE TESTTABLE");
    this.waitTillAllClear();
    getConnection().createStatement().execute(
        "CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
            + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
            + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
            + "jdbc:derby:newDB;create=true' "
            + "manualstart false) server groups (sg1)");
    getConnection()
        .createStatement()
        .execute(
            "CREATE GATEWAYSENDER mySender ( remotedsid 2 socketbuffersize 1000 "
                + "manualstart true  SOCKETREADTIMEOUT 1000 ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100) server groups (sg1)");

    getConnection().createStatement().execute(
        "CREATE TABLE TESTTABLE (ID INT NOT NULL) GATEWAYSENDER(mySender)" + getSQLSuffixClause());
    getConnection().createStatement().execute("DROP TABLE TESTTABLE");
    this.waitTillAllClear();
    getConnection()
        .createStatement()
        .execute(
            "CREATE TABLE TESTTABLE (ID INT NOT NULL) ASYNCEVENTLISTENER(myListener)" + getSQLSuffixClause());
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER(myListener)");
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET GATEWAYSENDER(mySender)");
  }
  
  public void testAttachDetachAsyncEventListener() throws Exception {
    getConnectionWithServerGroup();

    Statement derbyStmt = null;
    Statement stmt = null;
    try {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      derbyStmt
          .execute("CREATE TABLE TESTTABLE (ID INT NOT NULL, NAME VARCHAR(30))");
      getConnection().createStatement().execute(
          "CREATE TABLE TESTTABLE (ID INT NOT NULL, NAME VARCHAR(30))");

      stmt = getConnection().createStatement();
      // Create and start an asynceventlistener
      stmt.execute("CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
          + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
          + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
          + "jdbc:derby:newDB;create=true' "
          + "manualstart false) server groups (sg1)");

      // Attach the listener to a table
      stmt.execute("ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER(myListener)");

      // Add data and check that the listener did its job
      stmt.execute("INSERT INTO TESTTABLE VALUES(1, 'name1')");
      stmt.execute("INSERT INTO TESTTABLE VALUES(2, 'name2')");

      final AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue("MYLISTENER");

      final DistributedTestBase.WaitCriterion wc = new DistributedTestBase.WaitCriterion() {
        @Override
        public boolean done() {
          int sz = asyncQueue.getSender().getQueue().size();
          if (sz == 0) {
            return true;
          }
          else {
            getLogger().info("Got size as " + sz + " for 'MYLISTENER'");
            return false;
          }
        }

        @Override
        public String description() {
          return "waiting for queue for 'MYLISTENER' to drain";
        }
      };
      DistributedTestBase.waitForCriterion(wc, 30000, 500, true);
      // Compare results with derby
      validateResults(derbyStmt, stmt, "select * from testtable", false);

      // Create another listener and attach to the table without stopping or
      // removing the first one
      stmt.execute("CREATE ASYNCEVENTLISTENER anotherListener ( listenerclass "
          + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
          + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
          + "jdbc:derby:newDB;create=true' "
          + "manualstart false) server groups (sg1)");

      stmt.execute("ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER (anotherListener) ");

      // Add data again and check whether the new listener works
      stmt.execute("INSERT INTO TESTTABLE VALUES(3, 'name3')");
      stmt.execute("INSERT INTO TESTTABLE VALUES(4, 'name4')");

      final AsyncEventQueueImpl anotherAsyncQueue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue("ANOTHERLISTENER");

      final DistributedTestBase.WaitCriterion anotherWC = new DistributedTestBase.WaitCriterion() {
        @Override
        public boolean done() {
          int sz = anotherAsyncQueue.getSender().getQueue().size();
          if (sz == 0) {
            return true;
          }
          else {
            getLogger().info("Got size as " + sz + " for 'ANOTHERLISTENER'");
            return false;
          }
        }

        @Override
        public String description() {
          return "waiting for queue for 'ANOTHERLISTENER' to drain";
        }
      };
      DistributedTestBase.waitForCriterion(anotherWC, 30000, 500, true);

      // Compare results with derby
      validateResults(derbyStmt, stmt, "select * from testtable", false);

      // Drop the first listener
      stmt.execute("DROP ASYNCEVENTLISTENER myListener");

      // Detach the second listener
      stmt.execute("ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER () ");

      // Drop the second listener
      stmt.execute("DROP ASYNCEVENTLISTENER anotherListener");

      // Create the first one again
      stmt.execute("CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
          + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
          + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
          + "jdbc:derby:newDB;create=true' "
          + "manualstart false) server groups (sg1)");

      // Attach listener to the table again
      stmt.execute("ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER (myListener) ");

      stmt.execute("INSERT INTO TESTTABLE VALUES(5, 'name5')");
      stmt.execute("INSERT INTO TESTTABLE VALUES(6, 'name6')");

      // Get the queue reference again since it was removed, although the queue
      // name is the same
      final AsyncEventQueueImpl thirdAsyncQueue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue("MYLISTENER");

      final DistributedTestBase.WaitCriterion thirdWC = new DistributedTestBase.WaitCriterion() {
        @Override
        public boolean done() {
          int sz = thirdAsyncQueue.getSender().getQueue().size();
          if (sz == 0) {
            return true;
          }
          else {
            getLogger().info("Got size as " + sz + " for 'MYLISTENER'");
            return false;
          }
        }

        @Override
        public String description() {
          return "waiting for queue for 'MYLISTENER' to drain";
        }
      };
      DistributedTestBase.waitForCriterion(thirdWC, 30000, 500, true);
      // Compare results with derby
      validateResults(derbyStmt, stmt, "select * from testtable", false);

    } finally {
      try {
        // Drop everything
        if (derbyStmt != null) {
          derbyStmt.execute("DROP TABLE TESTTABLE");

        }
        if (stmt != null) {
          stmt.execute("DROP TABLE TESTTABLE");
          this.waitTillAllClear();
          stmt.execute("DROP ASYNCEVENTLISTENER myListener");
        }
      } catch (Exception e) {
        // Do nothing.
      }
    }
  }

  public void testStartStopAsyncEventListener() throws Exception {
    getConnectionWithServerGroup();

    Statement derbyStmt = null;
    Statement stmt = null;
    try {
      String derbyDbUrl = "jdbc:derby:newDB;create=true;";
      Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
      Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      derbyStmt
          .execute("CREATE TABLE TESTTABLE (ID INT NOT NULL, NAME VARCHAR(30))");
      stmt = getConnection().createStatement();
      // Create and start an asynceventlistener
      stmt.execute("CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
          + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
          + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
          + "jdbc:derby:newDB;create=true' "
          + "manualstart false) server groups (sg1)");

      // Attach the listener to a table
      stmt.execute("CREATE TABLE TESTTABLE (ID INT NOT NULL, NAME VARCHAR(30)) ASYNCEVENTLISTENER(myListener)");

      ResultSet hubRs = stmt
          .executeQuery("select * from SYS.SYSTABLES where tablename='TESTTABLE'");
      while (hubRs.next()) {
        Assert.assertEquals("MYLISTENER", hubRs.getString(17));
      }

      stmt.execute("INSERT INTO TESTTABLE VALUES(1, 'name1')");
      stmt.execute("INSERT INTO TESTTABLE VALUES(2, 'name2')");

      // wait for queue to drain
      final AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)Misc
          .getGemFireCache().getAsyncEventQueue("MYLISTENER");
      final DistributedTestBase.WaitCriterion wc = new DistributedTestBase.WaitCriterion() {
        @Override
        public boolean done() {
          int sz = asyncQueue.getSender().getQueue().size();
          if (sz == 0) {
            return true;
          }
          else {
            getLogger().info("Got size as " + sz + " for 'MYLISTENER'");
            return false;
          }
        }

        @Override
        public String description() {
          return "waiting for queue for 'MYLISTENER' to drain";
        }
      };
      DistributedTestBase.waitForCriterion(wc, 30000, 500, true);

      // Compare results with derby
      validateResults(derbyStmt, stmt, "select * from testtable", false);

      // Stop and restart the listener
      stmt.execute("CALL SYS.STOP_ASYNC_EVENT_LISTENER('myListener')");
      stmt.execute("CALL SYS.START_ASYNC_EVENT_LISTENER('myListener')");

      // Fire more inserts
      stmt.execute("INSERT INTO TESTTABLE VALUES(3, 'name3')");
      stmt.execute("INSERT INTO TESTTABLE VALUES(4, 'name4')");

      DistributedTestBase.waitForCriterion(wc, 30000, 500, true);
      validateResults(derbyStmt, stmt, "select * from testtable", false);

      // Stop and restart again
      stmt.execute("CALL SYS.STOP_ASYNC_EVENT_LISTENER('MYLISTENER')");
      stmt.execute("CALL SYS.START_ASYNC_EVENT_LISTENER('MYLISTENER')");

      // Fire more inserts and compare results again
      stmt.execute("INSERT INTO TESTTABLE VALUES(5, 'name5')");
      stmt.execute("INSERT INTO TESTTABLE VALUES(6, 'name6')");

      DistributedTestBase.waitForCriterion(wc, 30000, 500, true);
      validateResults(derbyStmt, stmt, "select * from testtable", false);

      // Stop the listener
      stmt.execute("CALL SYS.STOP_ASYNC_EVENT_LISTENER('myListener')");
    } finally {
      try {
        // Drop everything
        if (derbyStmt != null) {
          derbyStmt.execute("DROP TABLE TESTTABLE");

        }
        if (stmt != null) {
          stmt.execute("DROP TABLE TESTTABLE");
          this.waitTillAllClear();
          stmt.execute("DROP ASYNCEVENTLISTENER myListener");
        }
      } catch (Exception e) {
        // Do nothing.
      }
    }
  }

  public void testDropGatewaySenderAndDropDiskStore() throws SQLException {
    getConnectionWithServerGroup();
    char fileSeparator = System.getProperty("file.separator").charAt(0);
    GemFireCacheImpl cache = Misc.getGemFireCache();
    if (cache.findDiskStore("TESTDROPGATEWAYSENDERANDDROPDISKSTORE") == null) {
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
        stmt1.execute("Create DiskStore "
            + "testDropGatewaySenderAndDropDiskStore" + "'" + path + "'");
        conn1.close();
      }
      catch (SQLException e) {
        throw GemFireXDRuntimeException.newRuntimeException(null, e);
      }

    }
    getConnection().createStatement().execute(
        "CREATE ASYNCEVENTLISTENER myListener ( listenerclass "
            + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
            + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,"
            + "jdbc:derby:newDB;create=true' "
            + "manualstart false) server groups (sg1)");
    // 
    getConnection()
        .createStatement()
        .execute(
            "CREATE TABLE TESTTABLE (ID INT NOT NULL)  ASYNCEVENTLISTENER (myListener) PERSISTENT 'TESTDROPGATEWAYSENDERANDDROPDISKSTORE'" + getSQLSuffixClause());

    try {
      getConnection().createStatement().execute(
          "drop asynceventlistener mylistener");
      fail();
    }
    catch (SQLException e) {
      if (!"X0Y25".equals(e.getSQLState())) {
        throw e;
      }
    }
    try {
      getConnection().createStatement()
          .execute("drop gatewaysender mylistener");
      fail();
    }
    catch (SQLException e) {
      if (!"42Y55".equals(e.getSQLState())) {
        throw e;
      }
    }

    try {
      getConnection().createStatement().execute(
          "drop diskstore TESTDROPGATEWAYSENDERANDDROPDISKSTORE");
      fail();
    }
    catch (SQLException e) {
      if (!"X0Y25".equals(e.getSQLState())) {
        throw e;
      }
    }
    getConnection().createStatement().execute(
        "ALTER TABLE TESTTABLE SET ASYNCEVENTLISTENER () ");
    getConnection().createStatement().execute(
        "drop asynceventlistener mylistener");
    getConnection().createStatement().execute("drop table testtable");
    this.waitTillAllClear();
    getConnection().createStatement().execute(
        "drop diskstore testDropGatewaySenderAndDropDiskStore");
  }

  public void testBug46127CreateDup() throws Exception {
    // Try to create asynceventlisteners, gatewaysenders,
    // gatewayreceivers with dup names. Should throw object-exists errors
    getConnectionWithServerGroup();
    Connection conn = getConnection();

    conn.createStatement()
        .execute(
            "CREATE ASYNCEVENTLISTENER dup1 ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true'"
                + " manualstart false  ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100) server groups (sg1)");

    // Create it again
    try {
      conn.createStatement()
        .execute(
            "CREATE ASYNCEVENTLISTENER dup1 ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true'"
                + " manualstart false  ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100) server groups (sg1)");
      fail("CREATE ASYNCEVENTLISTENER dup1 should have failed");
    }
    catch (SQLException sqle) {
      if (!"X0Z22".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    conn.createStatement().execute("DROP ASYNCEVENTLISTENER dup1");

    // Now try gatewayreceiver
    conn.createStatement().execute("CREATE GATEWAYRECEIVER dup3 ");

    // Try to create another one with the same name
    try {
      conn.createStatement().execute("CREATE GATEWAYRECEIVER dup3 ");
      fail("CREATE GATEWAYRECEIVER dup3 should have failed");
    }
    catch (SQLException sqle) {
      if (!"X0Y68".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }

    conn.createStatement().execute("DROP GATEWAYRECEIVER dup3");
  }

  public void testCreateDropGWR_Bug46171() throws Exception
  {
    // This SQL script tests create and drop of gatewayreceivers
    // Cache object should not be orphaned, and unreachable bindaddress
    // should throw non-fatal error
    Object[][] Script_CreateDropGWR = {
        // First, test illegal bindaddress - should not drop connection
	{ "create gatewayreceiver x1 (bindaddress 'bleh')", "22008"},
        // Drop on nonexistent object
	{ "drop gatewayreceiver x1", "42Y55"},
        // LOCALHOST is always reachable
	{ "create gatewayreceiver x1 (bindaddress 'localhost')", null},
        // But X1 already exists...
	{ "create gatewayreceiver x1 (bindaddress 'localhost')", "X0Y68"},
        // Test drop-n-recreate (cache object should have been deleted by drop)
	{ "drop gatewayreceiver x1", null},
	{ "create gatewayreceiver x1 (bindaddress 'localhost')", null}
    };

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    // Go through the array, execute each string[0], check sqlstate [1]
    // This will fail on the first one that succeeds where it shouldn't
    // or throws unknown exception
    JDBC.SQLUnitTestHelper(stmt,Script_CreateDropGWR);

  }
  
  // Test bug #46845, users should not be allowed to drop DISKSTORE
  // if an ASYNCEVENTLISTENER is dependent on it (similar to DROP TABLE)
  public void testDropDiskStoreWithAsyncEventListener_46845() throws SQLException{
    getConnectionWithServerGroup();
    Connection conn = getConnection();

    // Make a diskstore
    conn.createStatement().execute("CREATE DISKSTORE TEST46845");
    // Make an async event listener that uses this diskstore
    conn.createStatement()
        .execute(
            "CREATE ASYNCEVENTLISTENER Test46845_Async ( listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
                + "initparams 'org.apache.derby.jdbc.EmbeddedDriver,jdbc:derby:newDB;create=true'"
                + " manualstart false  ENABLEBATCHCONFLATION true "
                + "BATCHSIZE 10 BATCHTIMEINTERVAL 100 ENABLEPERSISTENCE false MAXQUEUEMEMORY 400  "
                + "ALERTTHRESHOLD 100 DISKSTORENAME TEST46845) server groups (sg1)");
    // Try to drop the DISKSTORE - should fail as asynceventlistener is tied to it
    try {
      conn.createStatement().execute("DROP DISKSTORE TEST46845");
      fail("DROP DISKSTORE should have failed");
    }
    catch (SQLException sqle) {
      // Should fail with X0Y25 - object is in use
      if (!"X0Y25".equals(sqle.getSQLState())) {
        throw sqle;
      }
    }
   
    // Clean up
    conn.createStatement().execute("DROP ASYNCEVENTLISTENER IF EXISTS TEST46845_ASYNC");
    conn.createStatement().execute("DROP DISKSTORE TEST46845");
  }
  

  private Connection getConnectionWithServerGroup() throws SQLException {
    Properties info = new Properties();
    info.setProperty("server-groups", "SG1");
    Connection conn = getConnection(info);
    return conn;
  }

  public static class TestGatewayEventListener implements AsyncEventListener {
    public String test;

    public TestGatewayEventListener() {
    }

    public boolean processEvents(List<Event> events) {
      return false;
    }

    public void close() {
    }

    public void start() {

    }

    public void init(String initParamStr) {
      this.test = initParamStr;

    }
  }

  public static class TestGatewayEventListenerNotify implements
      AsyncEventListener {
    volatile private Event[] eventsProcessed;

    volatile private int eventsExpected;

    volatile private boolean allowRemoval = true;

    private int currNum = 0;

    private int numCallbacks = 0;

    private boolean encounteredException = false;

    public TestGatewayEventListenerNotify() {
      this.eventsProcessed = null;
      this.eventsExpected = -1;
    }

    Event[] getEventsCollected() {
      return eventsProcessed;
    }

    int getNumCallbacks() {
      return this.numCallbacks;
    }

    boolean exceptionOccurred() {
      return this.encounteredException;
    }

    public boolean processEvents(List<Event> events) {
      try {
        ++numCallbacks;

        if (eventsExpected == -1) {
          // Keep collecting the events receieved

          Event[] newarray = new Event[(eventsProcessed != null ? eventsProcessed.length
              : 0)
              + events.size()];
          int k = 0;
          if (eventsProcessed != null) {
            for (Event ev : eventsProcessed) {
              newarray[k++] = ev;
            }
          }
          for (Event ev : events) {
            newarray[k++] = ev;
          }
          eventsProcessed = newarray;

          synchronized (TestGatewayEventListenerNotify.class) {
            TestGatewayEventListenerNotify.class.notify();
          }

        }
        else {
          Iterator<Event> itr = events.iterator();

          while (itr.hasNext()) {
            Event event = itr.next();
            this.eventsProcessed[currNum++] = event;
          }
          if (currNum == this.eventsExpected) {
            synchronized (TestGatewayEventListenerNotify.class) {
              TestGatewayEventListenerNotify.class.notify();
            }
          }

        }
        try {
          Thread.sleep(500);
        }
        catch (InterruptedException e) {

        }
        return allowRemoval;
      }
      catch (Exception e) {
        e.printStackTrace();
        encounteredException = true;
        return allowRemoval;
      }
    }

    public void close() {
    }

    public void start() {
    }

    public void init(String initParams) {
      StringTokenizer tokenizer = new StringTokenizer(initParams, ",");
      if (tokenizer.hasMoreTokens()) {
        allowRemoval = !"false".equalsIgnoreCase(tokenizer.nextToken());
      }
      if (tokenizer.hasMoreTokens()) {
        this.eventsExpected = Integer.parseInt(tokenizer.nextToken());
      }

      if (this.eventsExpected >= 0) {
        this.eventsProcessed = new Event[this.eventsExpected];
      }

    }
  }
  public void waitTillAllClear() {}
}
