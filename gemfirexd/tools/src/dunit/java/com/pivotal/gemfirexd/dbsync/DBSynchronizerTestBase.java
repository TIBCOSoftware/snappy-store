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
package com.pivotal.gemfirexd.dbsync;

import java.net.InetAddress;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.SQLRecoverableException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.Callable;

import junit.framework.Assert;

import org.apache.derby.drda.NetworkServerControl;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueFactoryImpl;
import com.gemstone.gemfire.cache.asyncqueue.internal.AsyncEventQueueImpl;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.RegionQueue;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.AsyncEventListener;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.callbacks.Event.Type;
import com.pivotal.gemfirexd.callbacks.RowLoader;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.ddl.wan.GfxdGatewayEventListener;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;

import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;

@SuppressWarnings("serial")
public class DBSynchronizerTestBase extends DistributedSQLTestBase{

  final static int DELETED_KEY = 1;

  static volatile boolean ok = false;

  protected int netPort;

  @Override
  public void setUp() throws Exception {
    super.setUp();
    netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
  }

  @Override
  protected void vmTearDown() throws Exception {
    ok = false;
    super.vmTearDown();
  }

  @Override
  protected String reduceLogging() {
    // these tests generate lots of logs, so reducing them
    return "config";
  }

  public DBSynchronizerTestBase(String name) {
    super(name);
  }
  
  protected void blockForValidation() throws Exception {
    blockForValidation(this.netPort);
  }
  
  public static void blockForValidation(int netPort) throws Exception {
    synchronized (DBSynchronizerTestBase.class) {
      while (!ok) {
        DBSynchronizerTestBase.class.wait(1000);
      }
    }
    String derbyDbUrl = "jdbc:derby://localhost:" + netPort
        + "/newDB;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);

    Statement derbyStmt = derbyConn.createStatement();

    org.apache.derby.iapi.services.monitor.Monitor.getStream().println(
        "<ExpectedException action=add>lock could not be obtained"
            + "</ExpectedException>");
    for (int tries = 1; tries <= 5; tries++) {
      try {
        derbyStmt.execute("drop trigger test_ok");
        break;
      } catch (SQLException sqle) {
        // ignore lock timeout here
        if (!sqle.getSQLState().startsWith("40XL")) {
          throw sqle;
        }
        else {
          System.gc();
        }
      }
    }
    org.apache.derby.iapi.services.monitor.Monitor.getStream().println(
        "<ExpectedException action=remove>lock could not be obtained"
            + "</ExpectedException>");
    derbyConn.commit();

    derbyStmt.execute("drop procedure validateTestEnd ");
    derbyConn.commit();

    derbyConn.close();
  }

  public static void waitForAsyncQueueFlush(String asycQueueId)
      throws InterruptedException {
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
    if (cache != null) {
      AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)cache
          .getAsyncEventQueue(asycQueueId);
      RegionQueue rq;
      while (asyncQueue != null && asyncQueue.isRunning()
          && asyncQueue.getSender() != null
          && (rq = asyncQueue.getSender().getQueue()) != null) {
        if (rq.size() == 0) {
          break;
        }
        Thread.sleep(500);
      }
    }
  }
 
  public String getDerbyURL() throws Exception {
    return getDerbyURL(this.netPort);
  }

  public static String getDerbyURL(int port) throws Exception {
    String derbyDbUrl = "jdbc:derby://localhost:" + port
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    return derbyDbUrl;
  }
  
  public NetworkServerControl startNetworkServer() throws Exception {
    return startNetworkServer(this.netPort);
  }

  public static NetworkServerControl startNetworkServer(final int netPort)
      throws Exception {
    getGlobalLogger().info(
        "Starting a Derby Network Server on localhost:" + netPort);
    NetworkServerControl server = new NetworkServerControl(
        InetAddress.getByName("localhost"), netPort);
    // send the output to derby logs
    server.start(SanityManager.GET_DEBUG_STREAM());
    // wait for n/w server to initialize completely
    while (true) {
      Thread.sleep(500);
      try {
        server.ping();
        break;
      }
      catch (Exception e) {
      }
    }
    server.logConnections(true);
    return server;
  }

  protected void createDerbyValidationArtefacts() throws Exception {
    createDerbyValidationArtefacts(this.netPort);
  }
  
  public static void createDerbyValidationArtefacts(int netPort) throws Exception {
    // Create DB locally & create schema
    ok = false;
    String derbyDbUrl = "jdbc:derby://localhost:" + netPort
        + "/newDB;create=true;";
    if (TestUtil.currentUserName != null) {
      derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
          + TestUtil.currentUserPassword + ';');
    }
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    Connection derbyConn = DriverManager.getConnection(derbyDbUrl);
    // create schema
    Statement derbyStmt = derbyConn.createStatement();
    final String schemaName = getCurrentDefaultSchemaName();
    ResultSet rs = derbyConn.getMetaData().getTables(null, schemaName,
        "TESTTABLE", null);
    boolean foundTesttable = false;
    while (rs.next()) {
      foundTesttable = rs.getString(3).equalsIgnoreCase("TESTTABLE")
          && rs.getString(2).equalsIgnoreCase(schemaName);
      if (foundTesttable) {
        // delete previous entries
        derbyStmt.executeUpdate("delete from TESTTABLE");
        break;
      }
    }
    if (!foundTesttable) {
      derbyStmt
          .execute("create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) ");
    }
    derbyStmt
        .execute("create procedure validateTestEnd( IN deletedPk INT) "
            + "LANGUAGE JAVA PARAMETER STYLE JAVA  "
            + "EXTERNAL NAME 'com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase.notifyThreadOnOK(java.lang.Integer)'  ");

    // create blocking trigger
    derbyStmt.execute("CREATE TRIGGER test_ok   AFTER  DELETE   ON  TESTTABLE "
        + "REFERENCING OLD AS DELETEDROW  FOR EACH  ROW MODE DB2SQL"
        + " call validateTestEnd(DELETEDROW.ID)");
    derbyConn.commit();
    derbyStmt.close();
    derbyConn.close();
  }

  




  static SerializableRunnable getExecutorToCheckListenerAttached(
      final String hubID) {
    SerializableRunnable checkListenerAttached = new SerializableRunnable(
        "Verify if listener attached") {
      @Override
      public void run() throws CacheException {
        AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)Misc
            .getGemFireCache().getAsyncEventQueue(hubID);
        assertNotNull(asyncQueue);
        assertEquals(asyncQueue.getSender().getAsyncEventListeners().size(), 1);
      }
    };
    return checkListenerAttached;
  }

  static SerializableRunnable getExecutorToCheckListenerNotAttached(
      final String hubID) {
    SerializableRunnable checkListenerNotAttached = new SerializableRunnable(
        "Verify listener not  attached") {
      @Override
      public void run() throws CacheException {
        GemFireCacheImpl cache = Misc.getGemFireCache();
        AsyncEventQueue asyncQueue = cache.getAsyncEventQueue(hubID);
        assertNull(asyncQueue);
      }
    };
    return checkListenerNotAttached;
  }

  public static Runnable getExecutorForWBCLConfiguration(
      final String serverGroups, final String ID, final String className,
      final String driverClass, final String dbUrl, final Boolean manualStart,
      final Integer batchSize, final Integer batchTimeInterval,
      final Boolean batchConflation, final Boolean enablePersistence,
      final Integer maxQueueMem, final String diskStoreName,
      final Integer alertHreshold, final String initParamtr,
      final Boolean isParallel) {
    SerializableRunnable wbclConfigurator = new SerializableRunnable(
        "WBCL Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          StringBuilder str = new StringBuilder();
          str.append("CREATE asyncEventListener ");
          str.append(ID);
          str.append(" ( listenerclass '" + className + "'");
          str.append(" initparams '"
              + (driverClass != null ? driverClass + "," : "") + dbUrl + "'");
          if (manualStart != null) {
            str.append(" MANUALSTART " + manualStart);
          }
          if (isParallel != null) {
            str.append(" ISPARALLEL " + isParallel);
          }
          if (batchConflation != null) {
            str.append(" ENABLEBATCHCONFLATION " + batchConflation);
          }
          if (batchSize != null) {
            str.append(" BATCHSIZE " + batchSize);
          }
          if (batchTimeInterval != null) {
            str.append(" BATCHTIMEINTERVAL " + batchTimeInterval);
          }
          if (enablePersistence != null) {
            str.append(" ENABLEPERSISTENCE " + enablePersistence);
          }
          if (diskStoreName != null) {
            str.append(" DISKSTORENAME " + diskStoreName);
          }
          if (maxQueueMem != null) {
            str.append(" MAXQUEUEMEMORY " + maxQueueMem);
          }
          if (alertHreshold != null) {
            str.append(" ALERTTHRESHOLD " + alertHreshold + ") ");
          }
          if (serverGroups != null) {
            str.append("SERVER GROUPS ( " + serverGroups + " )");
          }
          st.execute(str.toString());
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }

    };
    return wbclConfigurator;
  }

  Runnable getExecutorForWBCLConfigurationVerification(final String ID,
      final Integer batchSize, final Integer batchTimeInterval,
      final Boolean batchConflation, final Boolean enablePersistence,
      final Integer mqm, final String diskStoreName,
      final Integer alertHreshold, final String initParamStr) {
    SerializableRunnable wbclConfigVerifier = new SerializableRunnable(
        "WBCL Configuration Verifier") {
      @Override
      public void run() throws CacheException {

        AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)Misc
            .getGemFireCache().getAsyncEventQueue(ID);
        GatewaySender sender = asyncQueue.getSender();

        if (batchSize != null) {
          assertEquals(sender.getBatchSize(), batchSize.intValue());
        }
        else {
          assertEquals(sender.getBatchSize(), GatewaySender.DEFAULT_BATCH_SIZE);
        }
        if (batchTimeInterval != null) {
          assertEquals(sender.getBatchTimeInterval(),
              batchTimeInterval.intValue());
        }
        else {
          assertEquals(sender.getBatchTimeInterval(),
              AsyncEventQueueFactoryImpl.DEFAULT_BATCH_TIME_INTERVAL);
        }
        if (batchConflation != null) {
          assertEquals(sender.isBatchConflationEnabled(),
              batchConflation.booleanValue());
        }
        else {
          assertEquals(sender.isBatchConflationEnabled(),
              GatewaySender.DEFAULT_BATCH_CONFLATION);
        }

        if (mqm != null) {
          assertEquals(sender.getMaximumQueueMemory(), mqm.intValue());
        }
        else {
          assertEquals(sender.getMaximumQueueMemory(),
              GatewaySender.DEFAULT_MAXIMUM_QUEUE_MEMORY);
        }
        if (diskStoreName != null) {
          assertEquals(sender.getDiskStoreName(), diskStoreName);
        }
        else {
          assertEquals(GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME,
              sender.getDiskStoreName());
        }

        if (alertHreshold != null) {
          assertEquals(sender.getAlertThreshold(), alertHreshold.intValue());
        }
        else {
          assertEquals(sender.getAlertThreshold(),
              GatewaySender.DEFAULT_ALERT_THRESHOLD);
        }
        if (enablePersistence != null) {
          assertEquals(sender.isPersistenceEnabled(),
              enablePersistence.booleanValue());
        }
        else {
          assertEquals(sender.isPersistenceEnabled(),
              GatewaySender.DEFAULT_PERSISTENCE_ENABLED);
        }
        GfxdGatewayEventListener sgel = (GfxdGatewayEventListener)asyncQueue
            .getAsyncEventListener();
        AsyncEventListener ael = sgel.getAsyncEventListenerForTest();
        if (initParamStr != null) {
          if (ael instanceof MyListener) {
            assertEquals(initParamStr, ((MyListener)ael).getInitStr());
          }
          else {
            throw new IllegalStateException("Test is trying to verify the "
                + "init param string but not exposing means to access the "
                + "data from AsyncEventListener");
          }
        }
        else {
          if (ael instanceof MyListener) {
            assertNull(((MyListener)ael).getInitStr());
          }
        }
      }
    };
    return wbclConfigVerifier;
  }

  static Callable<?> getExecutorToCheckForHubRunningAndIsPrimary(
      final String id, final boolean shouldWBCLBeRunning) {

    SerializableCallable checkForHubRunningAndPrimary = new SerializableCallable(
        "Check Hub Running & is Primary") {
      public Object call() throws CacheException {

        try {
          boolean[] isHubRunningAndPrimary = new boolean[] { false, false };
          Cache cache = Misc.getGemFireCache();
          for (AsyncEventQueue asyncQueue : cache.getAsyncEventQueues()) {
            if (id.equals(asyncQueue.getId())) {
              isHubRunningAndPrimary[0] = asyncQueue.isRunning();
              isHubRunningAndPrimary[1] = asyncQueue.isPrimary();
              return isHubRunningAndPrimary;
            }
          }
          return isHubRunningAndPrimary;
        } catch (Exception e) {
          throw GemFireXDRuntimeException.newRuntimeException(null, e);
        }

      }
    };
    return checkForHubRunningAndPrimary;
  }

  static Callable<?> getExecutorToCheckWBCLRemoved(final String id) {

    SerializableCallable checkWBCLRemoved = new SerializableCallable(
        "Check WBCL Removed") {
      public Object call() throws CacheException {
        try {
          GemFireCacheImpl cache = Misc.getGemFireCache();
          return cache.getAsyncEventQueue(id) == null;
        } catch (Exception e) {
          throw GemFireXDRuntimeException.newRuntimeException(null, e);
        }
      }
    };
    return checkWBCLRemoved;
  }

  static Callable<?> getExecutorToCheckQueueEmpty(final String id) {

    SerializableCallable checkEmpty = new SerializableCallable(
        "Check WBCL Queue empty") {
      public Object call() throws CacheException {
        Boolean empty = Boolean.FALSE;
        try {
          GemFireCacheImpl cache = Misc.getGemFireCache();
          AsyncEventQueueImpl asyncQueue = (AsyncEventQueueImpl)cache
              .getAsyncEventQueue(id);
          if (asyncQueue != null) {
            empty = Boolean.valueOf(asyncQueue.getSender().getQueue() == null
                || asyncQueue.getSender().getQueue().size() == 0);
          }
          return empty;
        } catch (Exception e) {
          throw GemFireXDRuntimeException.newRuntimeException(null, e);
        }

      }
    };
    return checkEmpty;
  }
  
  public static Runnable doBatchInsert() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          conn.setAutoCommit(false);
          Statement st = conn.createStatement();
          st.addBatch("insert into TESTTABLE values(1,'desc1','Add1',1)");
          st.addBatch("insert into TESTTABLE values(2,'desc2','Add2',2)");
          st.executeBatch();
          conn.commit();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }
  
  public static Runnable doBatchUpdate() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          conn.setAutoCommit(false);
          Statement st = conn.createStatement();
          st.addBatch("Update TESTTABLE set DESCRIPTION = 'desc1Mod' where ID = 1");
          st.addBatch("Update TESTTABLE set DESCRIPTION = 'desc2Mod' where ID = 2");
          st.executeBatch();
          conn.commit();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }
  
  public static Runnable doBatchDelete() {
    SerializableRunnable senderConf = new SerializableRunnable(
        "Sender Configurator") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          conn.setAutoCommit(false);
          Statement st = conn.createStatement();
          st.addBatch("Delete from TESTTABLE where ID = 1");
          st.addBatch("Delete from TESTTABLE where ID = 2");
          st.executeBatch();
          conn.commit();
        } catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return senderConf;
  }

  public static Runnable startAsyncEventListener(final String id) {

    SerializableRunnable startWBCL = new SerializableRunnable(
        "Start WBCL") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          CallableStatement cs = conn
              .prepareCall("call SYS.START_ASYNC_EVENT_LISTENER (?)");
          cs.setString(1, id);
          cs.execute();
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return startWBCL;

  }

  public static Runnable stopAsyncEventListener(final String id) {

    SerializableRunnable stopWBCL = new SerializableRunnable(
        "Start WBCL") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          CallableStatement cs = conn
              .prepareCall("call SYS.STOP_ASYNC_EVENT_LISTENER (?)");
          cs.setString(1, id);
          cs.execute();
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return stopWBCL;
  }

  static Runnable dropAsyncEventListener(final String id) {

    SerializableRunnable removeWBCL = new SerializableRunnable(
        "Remove WBCL") {
      @Override
      public void run() throws CacheException {
        try {
          Connection conn = TestUtil.jdbcConn;
          conn.createStatement().execute("DROP ASYNCEVENTLISTENER " + id);
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return removeWBCL;
  }

  public static interface MyListener {
    String getInitStr();
  }
  
  public static class TestAsyncEventListener implements AsyncEventListener {
    @Override
    public boolean processEvents(List<Event> events) {
      return true;
    }

    @Override
    public void close() {
    }

    @Override
    public void init(String initParamStr) {
    }

    @Override
    public void start() {
    }
  }
  
  public static class TestStartStopGatewayEventListener implements AsyncEventListener {

    @Override
    public boolean processEvents(List<Event> events) {
      return false;
    }

    @Override
    public void close() {
      CallbackObserver observer = CallbackObserverHolder.getInstance();
      observer.asyncEventListenerClose();
    }

    @Override
    public void init(String initParamStr) {
    }

    @Override
    public void start() {
      CallbackObserver observer = CallbackObserverHolder.getInstance();
      observer.asyncEventListenerStart();
    }
    
  }

  public static class TestNewGatewayEventListener implements
      AsyncEventListener, MyListener {
    String initStr;

    public TestNewGatewayEventListener() {
    }

    public boolean processEvents(List<Event> events) {
      return false;
    }

    public void close() {
    }

    public void start() {

    }

    public void init(String initParamStr) {
      this.initStr = initParamStr;
    }

    public String getInitStr() {
      return this.initStr;
    }
  }

  public static class TestNewGatewayEventListenerNotify implements
      AsyncEventListener, MyListener {
    volatile private Event[] eventsProcessed;

    private int currEventNum = 0;

    volatile int eventsExpected;

    volatile boolean allowEventsRemoval = true;

    public String initStr;

    boolean exceptionOccured = false;

    private int numCallback = 0;

    public TestNewGatewayEventListenerNotify() {
      this.eventsProcessed = null;
      this.eventsExpected = -1;
    }

    public void setEventsExpected(Event[] events) {
      this.eventsProcessed = events;
      this.eventsExpected = events.length;
    }

    public void setEventsRemovalFlag(boolean allowEventsRemoval) {
      this.allowEventsRemoval = allowEventsRemoval;
      if (!this.allowEventsRemoval) {
        this.eventsExpected = -1;
      }
    }

    public boolean exceptionOccurred() {
      return this.exceptionOccured;
    }

    public Event[] getEvents() {
      return this.eventsProcessed;
    }

    public int getNumEventsProcessed() {
      return currEventNum;
    }

    public int getNumCallbacks() {
      return this.numCallback;
    }

    public boolean processEvents(List<Event> events) {
      ++numCallback;
      try {
        if (eventsExpected == -1) {
          synchronized (TestNewGatewayEventListenerNotify.class) {
            TestNewGatewayEventListenerNotify.class.notify();
          }
        }
        else {
          Iterator<Event> itr = events.iterator();

          while (itr.hasNext()) {
            Event event = itr.next();
            Type type = event.getType();
            switch (type) {
            case AFTER_INSERT:
            case AFTER_UPDATE:
              Assert.assertTrue(event.getNewRowsAsResultSet().next());
              Assert.assertNull(event.getOldRowAsResultSet());              
              break;
            case AFTER_DELETE:
              Assert.assertTrue(event.getOldRowAsResultSet().next());
              Assert.assertNull(event.getNewRowsAsResultSet());
              break;
            }
            this.eventsProcessed[currEventNum++] = event;
          }
          if (this.eventsExpected == this.currEventNum) {
            synchronized (TestNewGatewayEventListenerNotify.class) {
              TestNewGatewayEventListenerNotify.class.notify();
            }
          }
        }
      }
      catch (Exception e) {
        exceptionOccured = true;
        throw new GemFireXDRuntimeException(e);
      }

      return this.allowEventsRemoval;
    }

    public void close() {
    }

    public void start() {

    }

    public void init(String initParamStr) {
      this.initStr = initParamStr;

    }

    public String getInitStr() {
      return this.initStr;
    }
  }

  public static void notifyThreadOnOK(Integer deletedPK) {
    if (deletedPK.intValue() == DELETED_KEY) {
      synchronized (DBSynchronizerTestBase.class) {
        ok = true;
        DBSynchronizerTestBase.class.notifyAll();
      }
    }
  }

  public static Runnable getHubCreatorExecutor(final String servGrp,
      final String hubID, final Integer port, final Integer socketBufferSize,
      final Integer maxPingTimeIntrval, final String startUpPolicy,
      final Boolean manualStart) {
    Runnable hubCreator = new SerializableRunnable() {
      public void run() {
        try {
          Connection conn = TestUtil.jdbcConn;
          Statement st = conn.createStatement();
          StringBuilder str = new StringBuilder();
          str.append("CREATE gatewaysender ");
          str.append(hubID);
          str.append(" ( remotedsid 2 ");
          str.append(" MANUALSTART FALSE  maxqueuememory 100 ) ");
          if (servGrp != null) {
            str.append("SERVER GROUPS ( " + servGrp + " )");
          }
          st.execute(str.toString());
        }
        catch (SQLException sqle) {
          throw GemFireXDRuntimeException.newRuntimeException(null, sqle);
        }
      }
    };
    return hubCreator;
  }
  void checkHubRunningAndIsPrimaryVerifier(String id) {

    final Callable<?> hubRunningAndIsPrimaryVerifier =
        getExecutorToCheckForHubRunningAndIsPrimary(id, true);
    waitForCriterion(new WaitCriterion() {
      @Override
      public boolean done() {
        try {
          boolean[] isHubRunningAndPrimary1 = (boolean[])serverExecute(1,
              hubRunningAndIsPrimaryVerifier);
          boolean[] isHubRunningAndPrimary2 = (boolean[])serverExecute(2,
              hubRunningAndIsPrimaryVerifier);
          return isHubRunningAndPrimary2[0] && isHubRunningAndPrimary1[0]
              && (isHubRunningAndPrimary1[1] || isHubRunningAndPrimary2[1])
              && !(isHubRunningAndPrimary1[1] && isHubRunningAndPrimary2[1]);
        } catch (Exception ex) {
          return false;
        }
      }

      @Override
      public String description() {
        boolean[] isHubRunningAndPrimary1 = null;
        boolean[] isHubRunningAndPrimary2 = null;
        try {
          isHubRunningAndPrimary1 = (boolean[])serverExecute(1,
              hubRunningAndIsPrimaryVerifier);
          isHubRunningAndPrimary2 = (boolean[])serverExecute(2,
              hubRunningAndIsPrimaryVerifier);
        } catch (Exception ex) {
          // ignore here
        }
        return "waiting for primary and secondary hubs to start "
            + "isHubRunningAndPrimary1="
            + Arrays.toString(isHubRunningAndPrimary1)
            + ", isHubRunningAndPrimary2="
            + Arrays.toString(isHubRunningAndPrimary2);
      }
    }, 20000, 500, true);
  }

  protected Connection createOraConnection(String oraUrl, String oraUser,
      String oraPasswd) throws SQLException {
    // retry for connection reset
    for (int tries = 1; tries <= 5; tries++) {
      try {
        return DriverManager.getConnection(oraUrl, oraUser, oraPasswd);
      } catch (SQLRecoverableException sqlre) {
        // retry
        continue;
      }
    }
    return DriverManager.getConnection(oraUrl, oraUser, oraPasswd);
  }

  public static class GfxdTestRowLoader implements RowLoader {

    private String params;

    /**
     * Very simple implementation which will load a value of 1 for all the
     * columns of the table.
     */
    public Object getRow(String schemaName, String tableName,
        Object[] primarykey) throws SQLException {
      SanityManager.DEBUG_PRINT("GfxdTestRowLoader", "load called with key="
          + primarykey[0] + " in VM "
          + Misc.getDistributedSystem().getDistributedMember());
      Integer num = (Integer)primarykey[0];
      Object[] values = new Object[] { num, "DESC" + num, "ADDR" + num, num };
      return values;
    }

    public String getParams() {
      return this.params;
    }

    public void init(String initStr) throws SQLException {
      this.params = initStr;
    }
  }

}
