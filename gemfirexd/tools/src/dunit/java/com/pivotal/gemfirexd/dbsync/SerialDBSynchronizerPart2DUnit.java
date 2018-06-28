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

import java.io.File;
import java.io.FileOutputStream;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.wan.AbstractGatewaySender;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.callbacks.AsyncEventHelper;
import com.pivotal.gemfirexd.callbacks.AsyncEventListener;
import com.pivotal.gemfirexd.callbacks.Event;
import com.pivotal.gemfirexd.callbacks.TableMetaData;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.jdbc.GfxdCallbacksTest;
import com.pivotal.gemfirexd.tools.GfxdSystemAdmin;
import io.snappydata.test.dunit.AsyncInvocation;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.derby.shared.common.error.ShutdownException;
import org.apache.derbyTesting.junit.JDBC;

@SuppressWarnings("serial")
public class SerialDBSynchronizerPart2DUnit extends DBSynchronizerTestBase {

  public SerialDBSynchronizerPart2DUnit(String name) {
    super(name);
  }

  public void DISABLED_testOracle_UseCase1_2() throws Throwable {
    final int isolationLevel = PartitionedRegion.rand.nextBoolean()
        ? Connection.TRANSACTION_READ_COMMITTED
        : Connection.TRANSACTION_NONE;
    startVMs(1, 2, 0, "CHANNELDATAGRP", null);
    final int netPort = startNetworkServer(1, null, null);
    Connection conn = TestUtil.getNetConnection(netPort, null, null);
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TABLE gemfire.SECL_BO_DATA2 ("
        + "       BO_TXN_ID VARCHAR (36) NOT NULL primary key,"
        + "       LAST_UPDATE_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP )"
        + "      PARTITION BY COLUMN (BO_TXN_ID)"
        + "      REDUNDANCY 1 "
        + "       EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW"
        + "      PERSISTENT ASYNCHRONOUS");

    stmt.execute("CREATE TABLE gemfire.SECL_BO_DATA_STATUS_HIST2 ("
        + "       BO_TXN_ID VARCHAR (36) NOT NULL ,"
        + "       CLIENT_ID VARCHAR (100) ,"
        + "       CLIENT_NAME VARCHAR (100) ,"
        + "       CLIENT_ACCOUNT VARCHAR (100) ,"
        + "       COMPANY_ID VARCHAR (100) ,"
        + "       CLIENT_REF_NO VARCHAR (100) ,"
        + "       VALUE_DATE TIMESTAMP ,"
        + "       AMOUNT DECIMAL (16,2) ,"
        + "       CURRENCY VARCHAR (20) ,"
        + "       ORIG_BANK_ID VARCHAR (100) ,"
        + "       BACKOFFICE_CODE VARCHAR (100) NOT NULL ,"
        + "       BENE_ACCNT_NO VARCHAR (100) ,"
        + "       BENE_NAME VARCHAR (100) ,"
        + "       BENE_ADDR VARCHAR (256) ,"
        + "       BENE_BANK_ID VARCHAR (100) ,"
        + "       BENE_BANK_NAME VARCHAR (100) ,"
        + "       BENE_BANK_ADDR VARCHAR (256) ,"
        + "       INSTR_CREATED_TIME TIMESTAMP ,"
        + "       INSTR_CREATED_BY VARCHAR (100) ,"
        + "       DATA_LIFE_STATUS SMALLINT WITH DEFAULT 0 ,"
        + "       MATCH_STATUS SMALLINT WITH DEFAULT 0 ,"
        + "       MATCH_DATE TIMESTAMP ,"
        + "       MATCH_CATEG_ID INTEGER ,"
        + "       MATCHING_TIME INTEGER WITH DEFAULT -1 ,"
        + "       MANUAL_MATCH CHAR (1) WITH DEFAULT 'N' ,"
        + "       MATCHING_REASON VARCHAR (128) ,"
        + "       SCREENING_TIME INTEGER NOT NULL ,"
        + "       IS_MANUAL CHAR (1) WITH DEFAULT 'N' ,"
        + "       IS_RESENT CHAR (1) WITH DEFAULT 'N' ,"
        + "       CHANNEL_NAME VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "       TXN_TYPE VARCHAR (30) WITH DEFAULT 'UNKNOWN' ,"
        + "       OFAC_MSG_ID VARCHAR (64) NOT NULL ,"
        + "       HIT_STATUS SMALLINT ,"
        + "       FILE_TYPE VARCHAR (36) WITH DEFAULT 'NA',"
        + "       ACTUAL_VALUE_DATE TIMESTAMP ,"
        + "       LAST_UPDATE_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP,"
        + "       FOREIGN KEY (BO_TXN_ID) REFERENCES gemfire.SECL_BO_DATA2(BO_TXN_ID))"
        + "      PARTITION BY COLUMN (BO_TXN_ID)"
        + "      REDUNDANCY 1 "
        + "       EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW"
        + "      PERSISTENT ASYNCHRONOUS");

    /**
     * check the new password encryption capability which is DS specific
     */
    final String oraUser = "gemfire";
    final String oraPasswd = "lu5Pheko";
    final String oraUrl = "jdbc:oracle:thin:@(DESCRIPTION=(CONNECT_TIMEOUT=60)"
        + "(ADDRESS=(PROTOCOL=TCP)(HOST=oracle.gemstone.com)"
        + "(PORT=1521))(CONNECT_DATA=(SERVICE_NAME=XE)))";
    final String encryptedPassword1 = new GfxdSystemAdmin().encryptForExternal(
        "encrypt-password",
        Arrays.asList(new String[] { "-locators=" + getDUnitLocatorString() }),
        oraUser, oraPasswd);
    final Throwable[] failure = new Throwable[1];

    Thread t = new Thread(new Runnable() {
      public void run() {
        // try a few times in a loop
        ExpectedException expectedEx = addExpectedException(
            "java.sql.SQLRecoverableException");
        for (int tries = 1; tries <= 5; tries++) {
          try {
            Connection conn = TestUtil.getConnection();
            Statement stmt = conn.createStatement();
            stmt.execute("create asynceventlistener "
                + "SECL_BO_DATA_STATUS_HIST_SYNC(listenerclass "
                + "'com.jpmorgan.tss.securitas.strategic."
                + "logsynctable.db.gemfirexd.callbacks.SectDBSynchronizer' "
                + "initparams    'oracle.jdbc.driver.OracleDriver," + oraUrl
                + ',' + oraUser + ',' + encryptedPassword1
                + "' ENABLEPERSISTENCE true MANUALSTART false "
                + "ALERTTHRESHOLD 2000) SERVER GROUPS(CHANNELDATAGRP)");
            failure[0] = null;
            break;
          } catch (Throwable t) {
            failure[0] = t;
          }
        }
        expectedEx.remove();
      }
    });

    t.start();
    Connection oraConn = createOraConnection(oraUrl, oraUser, oraPasswd);
    Statement oraStmt = oraConn.createStatement();
    t.join();
    if (failure[0] != null) {
      throw failure[0];
    }

    try {
      // create tables if not already created
      oraStmt.execute("CREATE TABLE gemfire.SECL_BO_DATA2 ("
          + "    BO_TXN_ID VARCHAR2(36) NOT NULL primary key,"
          + "    LAST_UPDATE_TIME TIMESTAMP)");

      oraStmt.execute("CREATE TABLE gemfire.SECL_BO_DATA_STATUS_HIST2("
          + "    BO_TXN_ID VARCHAR2(36) NOT NULL,"
          + "    CLIENT_ID VARCHAR2(100),"
          + "    CLIENT_NAME VARCHAR2(100),"
          + "    CLIENT_ACCOUNT VARCHAR2(100),"
          + "    COMPANY_ID VARCHAR2(100),"
          + "    CLIENT_REF_NO VARCHAR2(100),"
          + "    VALUE_DATE TIMESTAMP,"
          + "    AMOUNT NUMBER(16,2),"
          + "    CURRENCY VARCHAR2(20),"
          + "    ORIG_BANK_ID VARCHAR2(100),"
          + "    BACKOFFICE_CODE VARCHAR2(100) NOT NULL,"
          + "    BENE_ACCNT_NO VARCHAR2(100),"
          + "    BENE_NAME VARCHAR2(100),"
          + "    BENE_ADDR VARCHAR2(256),"
          + "    BENE_BANK_ID VARCHAR2(100),"
          + "    BENE_BANK_NAME VARCHAR2(100),"
          + "    BENE_BANK_ADDR VARCHAR2(256),"
          + "    INSTR_CREATED_TIME TIMESTAMP,"
          + "    INSTR_CREATED_BY VARCHAR2(100),"
          + "    DATA_LIFE_STATUS NUMBER(5) DEFAULT 0,"
          + "    MATCH_STATUS NUMBER(5) DEFAULT 0,"
          + "    MATCH_DATE TIMESTAMP,"
          + "    MATCH_CATEG_ID INTEGER,"
          + "    MATCHING_TIME INTEGER DEFAULT -1,"
          + "    MANUAL_MATCH CHAR(1) DEFAULT 'N',"
          + "    MATCHING_REASON VARCHAR2(128),"
          + "    SCREENING_TIME INTEGER NOT NULL,"
          + "    IS_MANUAL CHAR(1) DEFAULT 'N',"
          + "    IS_RESENT CHAR(1) DEFAULT 'N',"
          + "    CHANNEL_NAME VARCHAR2(100) DEFAULT 'UNKNOWN',"
          + "    TXN_TYPE VARCHAR2(30) DEFAULT 'UNKNOWN',"
          + "    OFAC_MSG_ID VARCHAR2(64) NOT NULL,"
          + "    HIT_STATUS NUMBER(5),"
          + "    FILE_TYPE VARCHAR2(36) DEFAULT 'NA',"
          + "    ACTUAL_VALUE_DATE TIMESTAMP,"
          + "    LAST_UPDATE_TIME TIMESTAMP,"
          + "    FOREIGN KEY (BO_TXN_ID) "
          + "      REFERENCES gemfire.SECL_BO_DATA2(BO_TXN_ID)"
          + ")");

      oraConn.commit();
    } catch (SQLException sqle) {
      // ignore exception
    }

    // first acquire a lock row to protect against concurrent dunit runs
    // just "lock table" will not do since we want to lock across connections
    // i.e. for the DBSynchronizer conn too, till end of the test
    oraConn.setAutoCommit(false);
    final String lockKey = "DBSP_LOCK";
    boolean locked = false;
    try {
      int numTries = 0;
      while (!locked) {
        oraStmt.execute("lock table gemfire.SECL_BO_DATA2 "
            + "in exclusive mode");
        ResultSet rs = oraStmt.executeQuery("select count(*) from gemfire."
            + "SECL_BO_DATA2 where BO_TXN_ID='" + lockKey + "'");
        rs.next();
        if (rs.getInt(1) == 0 || ++numTries > 100) {
          // clear the table
          oraStmt.execute("truncate table gemfire.SECL_BO_DATA_STATUS_HIST2");
          oraStmt.execute("delete from gemfire.SECL_BO_DATA2");
          oraStmt.execute("INSERT INTO gemfire.SECL_BO_DATA2 "
              + "(BO_TXN_ID) values ('" + lockKey + "')");
          locked = true;
        }
        else {
          Thread.sleep(5000);
        }
        oraConn.commit();
      }

      stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA2 "
          + "SET  asynceventlistener(SECL_BO_DATA_STATUS_HIST_SYNC)");
      stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST2 "
          + "SET  asynceventlistener(SECL_BO_DATA_STATUS_HIST_SYNC)");
      conn.setTransactionIsolation(isolationLevel);
      PreparedStatement pstmt2 = conn.prepareStatement("INSERT INTO "
          + "  gemfire.SECL_BO_DATA_STATUS_HIST2 (    "
          + "  BO_TXN_ID,   CLIENT_ID,    CLIENT_NAME,  CLIENT_ACCOUNT, "
          + "      COMPANY_ID,  CLIENT_REF_NO,    VALUE_DATE, AMOUNT,  "
          + "       CURRENCY,       ORIG_BANK_ID,   BACKOFFICE_CODE,    "
          + " BENE_ACCNT_NO,  BENE_NAME,    BENE_ADDR,   BENE_BANK_ID, "
          + "  BENE_BANK_NAME, BENE_BANK_ADDR, INSTR_CREATED_TIME,    "
          + " INSTR_CREATED_BY,  DATA_LIFE_STATUS,       MATCH_STATUS,  "
          + " MATCH_DATE, MATCH_CATEG_ID, MATCHING_TIME,  MANUAL_MATCH,  "
          + " MATCHING_REASON,   SCREENING_TIME, IS_MANUAL,    IS_RESENT, "
          + "     CHANNEL_NAME,   TXN_TYPE,   OFAC_MSG_ID, HIT_STATUS,  "
          + " ACTUAL_VALUE_DATE, LAST_UPDATE_TIME) values(?,?,?,?,?,?,?,?,"
          + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
          + "CURRENT_TIMESTAMP)");

      SerializableRunnable parentInserts = new SerializableRunnable() {
        @Override
        public void run() {
          try {
            Connection conn = TestUtil.getNetConnection(netPort, null, null);
            conn.setTransactionIsolation(isolationLevel);
            PreparedStatement pstmt = conn.prepareStatement("INSERT INTO "
                + "  gemfire.SECL_BO_DATA2 (    "
                + "  BO_TXN_ID, LAST_UPDATE_TIME) values(?,CURRENT_TIMESTAMP)");
            for (int i = 1; i <= 200; i++) {
              pstmt.setObject(1, "09824f04-26ef-49b0-95b2-955d3742"
                  + String.format("%04d", i));
              assertEquals(1, pstmt.executeUpdate());
              conn.commit();
            }
            conn.commit();
          } catch (Throwable t) {
            getLogWriter().error(t);
            throw new CacheException(t) {
            };
          }
        }
      };
      VM serverVM = getServerVM(2);
      AsyncInvocation async1 = serverVM.invokeAsync(parentInserts);

      ArrayList<String> childInserts = new ArrayList<String>(100);
      for (int i = 1; i <= 200; i++) {
        String txId = "09824f04-26ef-49b0-95b2-955d3742"
            + String.format("%04d", i);
        pstmt2.setObject(1, txId);
        pstmt2.setObject(2, "party name");
        pstmt2.setObject(3, null);
        pstmt2.setObject(4, "12345678");
        pstmt2.setObject(5, "1874563");
        pstmt2.setObject(6, "PB130482");
        pstmt2.setTimestamp(7, Timestamp.valueOf("2012-07-18 00:00:00.0"));
        pstmt2.setObject(8, "158.26");
        pstmt2.setObject(9, "CAD");
        pstmt2.setObject(10, "CHASGB2LXXX");
        pstmt2.setObject(11, "IPAY");
        pstmt2.setObject(12, null);
        pstmt2.setObject(13, null);
        pstmt2.setObject(14, null);
        pstmt2.setObject(15, null);
        pstmt2.setObject(16, null);
        pstmt2.setObject(17, null);
        pstmt2.setObject(18, Timestamp.valueOf("2013-03-13 14:20:04.05"));
        pstmt2.setObject(19, null);
        pstmt2.setObject(20, 1);
        pstmt2.setObject(21, 1);
        pstmt2.setObject(22, Timestamp.valueOf("2013-03-13 14:20:04.28"));
        pstmt2.setObject(23, 2);
        pstmt2.setObject(24, 0);
        pstmt2.setObject(25, "N");
        pstmt2.setObject(26, null);
        pstmt2.setObject(27, -1);
        pstmt2.setObject(28, "N");
        pstmt2.setObject(29, "N");
        pstmt2.setObject(30, "PYS");
        pstmt2.setObject(31, "UNKNOWN");
        pstmt2.setObject(32,
            "MITHUN0621                                                      ");
        pstmt2.setObject(33, null);
        pstmt2.setObject(34, Timestamp.valueOf("2010-03-19 00:00:00.0"));
        // pstmt.addBatch();
        // pstmt.executeBatch();
        try {
          assertEquals(1, pstmt2.executeUpdate());
          conn.commit();
          childInserts.add(txId);
        } catch (SQLException sqle) {
          if (!"X0Z02".equals(sqle.getSQLState())
              && !"23503".equals(sqle.getSQLState())) {
            throw sqle;
          }
        }
      }

      async1.getResult();
      // check updated in Oracle
      stmt.execute("call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH("
          + "'SECL_BO_DATA_STATUS_HIST_SYNC', 1, 0)");
      conn.commit();

      ResultSet rs = oraStmt.executeQuery("select * from "
          + "gemfire.SECL_BO_DATA2 where BO_TXN_ID <> '" + lockKey
          + "' order by BO_TXN_ID");
      for (int i = 1; i <= 200; i++) {
        assertTrue(rs.next());
        assertEquals(
            "09824f04-26ef-49b0-95b2-955d3742" + String.format("%04d", i),
            rs.getString(1));
      }
      assertFalse(rs.next());
      rs.close();
      rs = oraStmt.executeQuery("select * from "
          + "gemfire.SECL_BO_DATA_STATUS_HIST2 order by BO_TXN_ID");
      for (int i = 1; i <= childInserts.size(); i++) {
        assertTrue(rs.next());
        assertEquals(childInserts.get(i - 1), rs.getString(1));
      }
      assertFalse(rs.next());
      rs.close();

    } finally {
      try {
        oraConn.commit();
      } catch (SQLException sqle) {
        // ignore at this point
      }
      if (locked) {
        // try hard to release the lock
        for (;;) {
          try {
            oraStmt.execute("delete from gemfire.SECL_BO_DATA2 "
                + "where BO_TXN_ID='" + lockKey + "'");
            break;
          } catch (SQLException sqle) {
            // retry in case of exception
            try {
              oraStmt.close();
            } catch (SQLException e) {
            }
            try {
              oraConn.commit();
            } catch (SQLException e) {
            }
            try {
              oraConn.close();
            } catch (SQLException e) {
            }
            oraConn = createOraConnection(oraUrl, oraUser, oraPasswd);
            oraStmt = oraConn.createStatement();
          }
        }
      }
      try {
        oraStmt.execute("truncate table gemfire.SECL_BO_DATA_STATUS_HIST2");
        oraConn.commit();
      } catch (SQLException sqle) {
        // ignore at this point
      }
      try {
        oraStmt.execute("delete from gemfire.SECL_BO_DATA2");
        oraConn.commit();
      } catch (SQLException sqle) {
        // ignore at this point
      }
      try {
        oraConn.close();
      } catch (SQLException sqle) {
        // ignore at this point
      }
    }
    conn.commit();
    stmt.close();
    conn.close();
  }

  // Disabled due to bug 48537
  public void testExternalDBSyncPropertiesFile() throws Throwable {
    startVMs(1, 2, 0, "CHANNELDATAGRP", null);
    int netPort = startNetworkServer(1, null, null);
    Connection conn = TestUtil.getNetConnection(netPort, null, null);
    Statement stmt = conn.createStatement();
    stmt.execute("CREATE TABLE gemfire.SECL_BO_DATA_STATUS_HIST ("
        + "       BO_TXN_ID VARCHAR (36) NOT NULL ,"
        + "       CLIENT_ID VARCHAR (100) ,"
        + "       CLIENT_NAME VARCHAR (100) ,"
        + "       CLIENT_ACCOUNT VARCHAR (100) ,"
        + "       COMPANY_ID VARCHAR (100) ,"
        + "       CLIENT_REF_NO VARCHAR (100) ,"
        + "       VALUE_DATE TIMESTAMP ,"
        + "       AMOUNT DECIMAL (16,2) ,"
        + "       CURRENCY VARCHAR (20) ,"
        + "       ORIG_BANK_ID VARCHAR (100) ,"
        + "       BACKOFFICE_CODE VARCHAR (100) NOT NULL ,"
        + "       BENE_ACCNT_NO VARCHAR (100) ,"
        + "       BENE_NAME VARCHAR (100) ,"
        + "       BENE_ADDR VARCHAR (256) ,"
        + "       BENE_BANK_ID VARCHAR (100) ,"
        + "       BENE_BANK_NAME VARCHAR (100) ,"
        + "       BENE_BANK_ADDR VARCHAR (256) ,"
        + "       INSTR_CREATED_TIME TIMESTAMP ,"
        + "       INSTR_CREATED_BY VARCHAR (100) ,"
        + "       DATA_LIFE_STATUS SMALLINT WITH DEFAULT 0 ,"
        + "       MATCH_STATUS SMALLINT WITH DEFAULT 0 ,"
        + "       MATCH_DATE TIMESTAMP ,"
        + "       MATCH_CATEG_ID INTEGER ,"
        + "       MATCHING_TIME INTEGER WITH DEFAULT -1 ,"
        + "       MANUAL_MATCH CHAR (1) WITH DEFAULT 'N' ,"
        + "       MATCHING_REASON VARCHAR (128) ,"
        + "       SCREENING_TIME INTEGER NOT NULL ,"
        + "       IS_MANUAL CHAR (1) WITH DEFAULT 'N' ,"
        + "       IS_RESENT CHAR (1) WITH DEFAULT 'N' ,"
        + "       CHANNEL_NAME VARCHAR (100) WITH DEFAULT 'UNKNOWN' ,"
        + "       TXN_TYPE VARCHAR (30) WITH DEFAULT 'UNKNOWN' ,"
        + "       OFAC_MSG_ID VARCHAR (64) NOT NULL ,"
        + "       HIT_STATUS SMALLINT ,"
        + "       FILE_TYPE VARCHAR (36) WITH DEFAULT 'NA',"
        + "       ACTUAL_VALUE_DATE TIMESTAMP ,"
        + "       LAST_UPDATE_TIME TIMESTAMP WITH DEFAULT CURRENT_TIMESTAMP )"
        + "      PARTITION BY COLUMN (BO_TXN_ID)"
        + "      REDUNDANCY 1 "
        + "       EVICTION BY LRUHEAPPERCENT EVICTACTION OVERFLOW"
        + "      PERSISTENT ASYNCHRONOUS");

    /**
     * check the new password encryption capability which is DS specific using
     * "secret=" argument or via the new external properties file in
     * DBSynchronizer
     */
    final String dbUser = "gemfire";
    final String dbPasswd = "lu5Pheko";

    // start derby server
    Class.forName("org.apache.derby.jdbc.EmbeddedDriver").newInstance();
    NetworkServerControl netServer = startNetworkServer();
    final String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
        + "/newDB;";
    Connection dbConn = DriverManager.getConnection(derbyDbUrl + "create=true",
        dbUser, dbPasswd);

    final String transformation = "Blowfish/CBC/NOPADDING";
    final int keySize = 64;
    final String encryptedPassword1 = new GfxdSystemAdmin().encryptForExternal(
        "encrypt-password",
        Arrays.asList(new String[] { "-locators=" + getDUnitLocatorString() }),
        dbUser, dbPasswd);
    final String encryptedPassword2 = new GfxdSystemAdmin().encryptForExternal(
        "encrypt-password",
        Arrays.asList(new String[] { "-locators=" + getDUnitLocatorString(),
            "-transformation=" + transformation, "-keysize=" + keySize }),
        dbUser, dbPasswd);
    final File dbFile1 = new File(".", "db1.props");
    // create an external properties file
    Properties dbProps = new Properties();
    dbProps.setProperty("user", dbUser);
    dbProps.setProperty("Password", encryptedPassword1);
    dbProps.setProperty("URL", derbyDbUrl);
    dbProps.setProperty("driver", "org.apache.derby.jdbc.ClientDriver");
    FileOutputStream out = new FileOutputStream(dbFile1);
    dbProps.store(out, "Generated file -- do not change manually");
    out.flush();
    out.close();
    final File dbFile2 = new File(".", "ora2.props");
    out = new FileOutputStream(dbFile2);
    dbProps.remove("Password");
    dbProps.setProperty("Secret", encryptedPassword2);
    dbProps.setProperty("transformation", transformation);
    dbProps.setProperty("KeySize", Integer.toString(keySize));
    dbProps.store(out, "Generated file -- do not change manually");
    out.flush();
    out.close();

    final Throwable[] failure = new Throwable[1];
    Thread t = new Thread(new Runnable() {
      public void run() {
        try {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          stmt.execute("create asynceventlistener "
              + "SECL_BO_DATA_STATUS_HIST_SYNC(listenerclass "
              + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
              + "initparams  'org.apache.derby.jdbc.ClientDriver," + derbyDbUrl
              + ',' + dbUser + ",secret=" + encryptedPassword1
              + "' ENABLEPERSISTENCE true MANUALSTART false "
              + "ALERTTHRESHOLD 2000) SERVER GROUPS(CHANNELDATAGRP)");
        } catch (Throwable t) {
          failure[0] = t;
        }
      }
    });

    t.start();
    Statement dbStmt = dbConn.createStatement();
    t.join();
    if (failure[0] != null) {
      throw failure[0];
    }

    // create table in derby
    dbStmt.execute("CREATE TABLE gemfire.SECL_BO_DATA_STATUS_HIST ("
        + "       BO_TXN_ID VARCHAR (36) NOT NULL ,"
        + "       CLIENT_ID VARCHAR (100) ,"
        + "       CLIENT_NAME VARCHAR (100) ,"
        + "       CLIENT_ACCOUNT VARCHAR (100) ,"
        + "       COMPANY_ID VARCHAR (100) ,"
        + "       CLIENT_REF_NO VARCHAR (100) ,"
        + "       VALUE_DATE TIMESTAMP ,"
        + "       AMOUNT DECIMAL (16,2) ,"
        + "       CURRENCY VARCHAR (20) ,"
        + "       ORIG_BANK_ID VARCHAR (100) ,"
        + "       BACKOFFICE_CODE VARCHAR (100) NOT NULL ,"
        + "       BENE_ACCNT_NO VARCHAR (100) ,"
        + "       BENE_NAME VARCHAR (100) ,"
        + "       BENE_ADDR VARCHAR (256) ,"
        + "       BENE_BANK_ID VARCHAR (100) ,"
        + "       BENE_BANK_NAME VARCHAR (100) ,"
        + "       BENE_BANK_ADDR VARCHAR (256) ,"
        + "       INSTR_CREATED_TIME TIMESTAMP ,"
        + "       INSTR_CREATED_BY VARCHAR (100) ,"
        + "       DATA_LIFE_STATUS SMALLINT WITH DEFAULT 0 ,"
        + "       MATCH_STATUS SMALLINT WITH DEFAULT 0 ,"
        + "       MATCH_DATE TIMESTAMP ,"
        + "       MATCH_CATEG_ID INTEGER ,"
        + "       MATCHING_TIME INTEGER WITH DEFAULT -1 ,"
        + "       MANUAL_MATCH CHAR (1) WITH DEFAULT 'N' ,"
        + "       MATCHING_REASON VARCHAR (128) ,"
        + "       SCREENING_TIME INTEGER NOT NULL ,"
        + "       IS_MANUAL CHAR (1) WITH DEFAULT 'N' ,"
        + "       IS_RESENT CHAR (1) WITH DEFAULT 'N' ,"
        + "       CHANNEL_NAME VARCHAR (100),"
        + "       TXN_TYPE VARCHAR (30),"
        + "       OFAC_MSG_ID VARCHAR (64) NOT NULL ,"
        + "       HIT_STATUS SMALLINT ,"
        + "       FILE_TYPE VARCHAR (36),"
        + "       ACTUAL_VALUE_DATE TIMESTAMP ,"
        + "       LAST_UPDATE_TIME TIMESTAMP)");

    try {
      stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST "
          + "SET  asynceventlistener(SECL_BO_DATA_STATUS_HIST_SYNC)");
      PreparedStatement pstmt = conn.prepareStatement("INSERT INTO "
          + "  gemfire.SECL_BO_DATA_STATUS_HIST (    "
          + "  BO_TXN_ID,   CLIENT_ID,    CLIENT_NAME,  CLIENT_ACCOUNT, "
          + "      COMPANY_ID,  CLIENT_REF_NO,    VALUE_DATE, AMOUNT,  "
          + "       CURRENCY,       ORIG_BANK_ID,   BACKOFFICE_CODE,    "
          + " BENE_ACCNT_NO,  BENE_NAME,    BENE_ADDR,   BENE_BANK_ID, "
          + "  BENE_BANK_NAME, BENE_BANK_ADDR, INSTR_CREATED_TIME,    "
          + " INSTR_CREATED_BY,  DATA_LIFE_STATUS,       MATCH_STATUS,  "
          + " MATCH_DATE, MATCH_CATEG_ID, MATCHING_TIME,  MANUAL_MATCH,  "
          + " MATCHING_REASON,   SCREENING_TIME, IS_MANUAL,    IS_RESENT, "
          + "     CHANNEL_NAME,   TXN_TYPE,   OFAC_MSG_ID, HIT_STATUS,  "
          + " ACTUAL_VALUE_DATE, LAST_UPDATE_TIME) values(?,?,?,?,?,?,?,?,"
          + "?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,"
          + "CURRENT_TIMESTAMP)");
      for (int i = 1; i <= 2000; i++) {
        pstmt.setObject(1,
            "09824f04-26ef-49b0-95b2-955d3742" + String.format("%04d", i));
        pstmt.setObject(2, "party name");
        pstmt.setObject(3, null);
        pstmt.setObject(4, "12345678");
        pstmt.setObject(5, "1874563");
        pstmt.setObject(6, "PB130482");
        pstmt.setTimestamp(7, Timestamp.valueOf("2012-07-18 00:00:00.0"));
        pstmt.setObject(8, "158.26");
        pstmt.setObject(9, "CAD");
        pstmt.setObject(10, "CHASGB2LXXX");
        pstmt.setObject(11, "IPAY");
        pstmt.setObject(12, null);
        pstmt.setObject(13, null);
        pstmt.setObject(14, null);
        pstmt.setObject(15, null);
        pstmt.setObject(16, null);
        pstmt.setObject(17, null);
        pstmt.setObject(18, Timestamp.valueOf("2013-03-13 14:20:04.05"));
        pstmt.setObject(19, null);
        pstmt.setObject(20, 1);
        pstmt.setObject(21, 1);
        pstmt.setObject(22, Timestamp.valueOf("2013-03-13 14:20:04.28"));
        pstmt.setObject(23, 2);
        pstmt.setObject(24, 0);
        pstmt.setObject(25, "N");
        pstmt.setObject(26, null);
        pstmt.setObject(27, -1);
        pstmt.setObject(28, "N");
        pstmt.setObject(29, "N");
        pstmt.setObject(30, "PYS");
        pstmt.setObject(31, "UNKNOWN");
        pstmt.setObject(32,
            "MITHUN0621                                                      ");
        pstmt.setObject(33, null);
        pstmt.setObject(34, Timestamp.valueOf("2010-03-19 00:00:00.0"));
        // pstmt.addBatch();
        // pstmt.executeBatch();
        assertEquals(1, pstmt.executeUpdate());
        conn.commit();
        // drop and recreate DBSynchronizer with external properties file
        if (i == 500) {
          // wait for queues to flush first
          stmt.execute("call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH("
              + "'SECL_BO_DATA_STATUS_HIST_SYNC', 1, 0)");
          conn.commit();
          stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST "
              + "SET asynceventlistener()");
          stmt.execute("DROP ASYNCEVENTLISTENER SECL_BO_DATA_STATUS_HIST_SYNC");
          stmt.execute("create asynceventlistener SECL_BO_DATA_STATUS_HIST_SYNC"
              + "(listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
              + "initparams 'file=" + dbFile1.getAbsolutePath()
              + "' ENABLEPERSISTENCE true MANUALSTART true ALERTTHRESHOLD 2000) "
              + "SERVER GROUPS(CHANNELDATAGRP)");
          stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST "
              + "SET  asynceventlistener(SECL_BO_DATA_STATUS_HIST_SYNC)");
          stmt.execute("call sys.start_async_event_listener("
              + "'SECL_BO_DATA_STATUS_HIST_SYNC')");
        }
        else if (i == 1000) {
          // wait for queues to flush first
          stmt.execute("call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH("
              + "'SECL_BO_DATA_STATUS_HIST_SYNC', 1, 0)");
          conn.commit();
          stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST "
              + "SET asynceventlistener()");
          stmt.execute("DROP ASYNCEVENTLISTENER SECL_BO_DATA_STATUS_HIST_SYNC");
          stmt.execute("create asynceventlistener "
              + "SECL_BO_DATA_STATUS_HIST_SYNC (listenerclass "
              + "'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
              + "initparams  'org.apache.derby.jdbc.ClientDriver," + derbyDbUrl
              + ',' + dbUser + ",secret=" + encryptedPassword2
              + ",Transformation=" + transformation + ",keySize=" + keySize
              + "' ENABLEPERSISTENCE true MANUALSTART true "
              + "ALERTTHRESHOLD 2000) SERVER GROUPS(CHANNELDATAGRP)");
          stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST "
              + "SET  asynceventlistener(SECL_BO_DATA_STATUS_HIST_SYNC)");
          stmt.execute("call sys.start_async_event_listener("
              + "'SECL_BO_DATA_STATUS_HIST_SYNC')");
        }
        else if (i == 1500) {
          // wait for queues to flush first
          stmt.execute("call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH("
              + "'SECL_BO_DATA_STATUS_HIST_SYNC', 1, 0)");
          conn.commit();
          stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST "
              + "SET asynceventlistener()");
          stmt.execute("DROP ASYNCEVENTLISTENER SECL_BO_DATA_STATUS_HIST_SYNC");
          stmt.execute("create asynceventlistener SECL_BO_DATA_STATUS_HIST_SYNC"
              + "(listenerclass 'com.pivotal.gemfirexd.callbacks.DBSynchronizer' "
              + "initparams 'file=" + dbFile2.getAbsolutePath()
              + "' ENABLEPERSISTENCE true MANUALSTART true ALERTTHRESHOLD 2000) "
              + "SERVER GROUPS(CHANNELDATAGRP)");
          stmt.execute("ALTER TABLE gemfire.SECL_BO_DATA_STATUS_HIST "
              + "SET  asynceventlistener(SECL_BO_DATA_STATUS_HIST_SYNC)");
          stmt.execute("call sys.start_async_event_listener("
              + "'SECL_BO_DATA_STATUS_HIST_SYNC')");
        }
      }

      // check updated in Derby
      stmt.execute("call SYS.WAIT_FOR_SENDER_QUEUE_FLUSH("
          + "'SECL_BO_DATA_STATUS_HIST_SYNC', 1, 0)");
      conn.commit();

      ResultSet rs = dbStmt.executeQuery("select * from "
          + "gemfire.SECL_BO_DATA_STATUS_HIST order by BO_TXN_ID");
      for (int i = 1; i <= 2000; i++) {
        assertTrue("no row found for i=" + i, rs.next());
        assertEquals(
            "09824f04-26ef-49b0-95b2-955d3742" + String.format("%04d", i),
            rs.getString(1));
      }
      assertFalse(rs.next());
      rs.close();

    } finally {
      try {
        dbConn.commit();
      } catch (SQLException sqle) {
        // ignore at this point
      }
      try {
        dbStmt.execute("drop table gemfire.SECL_BO_DATA_STATUS_HIST");
        dbConn.commit();
      } catch (SQLException sqle) {
        // ignore at this point
      }
      try {
        dbConn.close();
      } catch (SQLException sqle) {
        // ignore at this point
      }

      try {
        netServer.shutdown();
      } catch (Exception e) {
        // ignore
      }

      //noinspection ResultOfMethodCallIgnored
      dbFile1.delete();
      //noinspection ResultOfMethodCallIgnored
      dbFile2.delete();
    }
    conn.commit();
    stmt.close();
    conn.close();
  }

  public void testTransactionalBehaviourOfDBSynchronizer_1() throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(1, -1, "SG1");
      // create table
      clientSQLExecute(1, "create table TESTTABLE "
          + "(ID int not null primary key, DESCRIPTION varchar(1024), "
          + "ADDRESS varchar(1024), ID1 int) AsyncEventListener (WBCL1)");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      Connection conn = TestUtil.jdbcConn;
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      Statement stmt = conn.createStatement();
      // Do an insert in sql fabric. This will create a primary bucket on the
      // lone server VM
      // with bucket ID =1

      stmt.executeUpdate("Insert into TESTTABLE values(114,'desc114','Add114',114)");

      stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(227,'desc227','Add227',227)");
      stmt.executeUpdate("Insert into TESTTABLE values(340,'desc340','Add340',340)");
      conn.rollback();
      stmt.executeUpdate("Insert into TESTTABLE values(114,'desc114','Add114',114)");
      // Insert some more rows in gemfirexd
      stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(2,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(224,'desc227','Add227',227)");
      stmt.executeUpdate("Insert into TESTTABLE values(331,'desc340','Add340',340)");
      conn.commit();
      // Bulk Update
      stmt.executeUpdate("update TESTTABLE set ID1 = ID1 +1 ");
      conn.rollback();
      // PK delete
      stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY);
      conn.commit();
      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    } finally {
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (!sqle.getMessage().contains("shutdown")) {
          sqle.printStackTrace();
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testTransactionalBehaviourOfDBSynchronizer_2() throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      startVMs(1, 1, -1, "SG1", null);
      // create table
      clientSQLExecute(1, "create table TESTTABLE "
          + "(ID int not null primary key, DESCRIPTION varchar(1024), "
          + "ADDRESS varchar(1024), ID1 int) AsyncEventListener (WBCL1) ");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      Connection conn = TestUtil.jdbcConn;
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      Statement stmt = conn.createStatement();
      PreparedStatement pstmt1 = conn
          .prepareStatement("insert into testtable values(?,?,?,?)");
      for (int i = 1; i < 10; ++i) {
        pstmt1.setInt(1, i);
        pstmt1.setString(2, "desc" + i);
        pstmt1.setString(3, "Add" + i);
        pstmt1.setInt(4, i);
        assertEquals(1, pstmt1.executeUpdate());
      }
      conn.commit();
      PreparedStatement pstmt2 = conn
          .prepareStatement("update testtable set ID1 = ? where description = ?");
      for (int i = 1; i < 5; ++i) {
        pstmt2.setInt(1, i * 10);
        pstmt2.setString(2, "desc" + i);
        assertEquals(1, pstmt2.executeUpdate());
      }
      conn.commit();
      PreparedStatement pstmt3 = conn
          .prepareStatement("select ID1 from TestTable where description = ?");
      for (int i = 1; i < 5; ++i) {
        pstmt3.setString(1, "desc" + i);
        ResultSet rs = pstmt3.executeQuery();
        assertTrue(rs.next());
        assertEquals(i * 10, rs.getInt(1));
      }
      // Now a delete a row & reinsert the row but with changed data . The
      // deletion should go via bulk op route
      assertEquals(1,
          stmt.executeUpdate("delete from TESTTABLE where ADDRESS = 'Add5'"));
      // Insert a row corresponding to the row deleted with Address as Add5
      pstmt1.setInt(1, 5);
      pstmt1.setString(2, "desc.5");
      pstmt1.setString(3, "Add5");
      pstmt1.setInt(4, 5);
      assertEquals(1, pstmt1.executeUpdate());
      conn.commit();
      // PK delete
      stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY);
      conn.commit();
      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    } finally {
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testTransactionalBehaviourOfAsyncEventListener_1()
      throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(1, -1, "SG1");
      // create table
      clientSQLExecute(1, "create table TESTTABLE (ID int not null "
          + "primary key, DESCRIPTION varchar(1024), ADDRESS varchar(1024), "
          + "ID1 int) AsyncEventListener (WBCL1)");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          TXAsyncEventListener.class.getName(), null, derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
          null, false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      Connection conn = TestUtil.jdbcConn;
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      Statement stmt = conn.createStatement();
      // Do an insert in sql fabric. This will create a primary bucket on the
      // lone server VM with bucket ID =1

      stmt.executeUpdate("Insert into TESTTABLE values(114,'desc114','Add114',114)");

      stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(227,'desc227','Add227',227)");
      stmt.executeUpdate("Insert into TESTTABLE values(340,'desc340','Add340',340)");
      conn.rollback();
      stmt.executeUpdate("Insert into TESTTABLE values(114,'desc114','Add114',114)");
      // Insert some more rows in gemfirexd
      stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(2,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(224,'desc227','Add227',227)");
      stmt.executeUpdate("Insert into TESTTABLE values(331,'desc340','Add340',340)");
      conn.commit();
      // Bulk Update
      stmt.executeUpdate("update TESTTABLE set ID1 = ID1 +1 ");
      conn.rollback();
      // PK delete
      stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY);
      conn.commit();
      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    } finally {
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testTransactionalBehaviourOfAsyncEventListener_2()
      throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      startVMs(1, 1, -1, "SG1", null);
      // create table
      clientSQLExecute(1, "create table TESTTABLE (ID int not null "
          + "primary key, DESCRIPTION varchar(1024), ADDRESS varchar(1024), "
          + "ID1 int) AsyncEventListener (WBCL1)");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          TXAsyncEventListener.class.getName(), null, derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
          null, false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      Connection conn = TestUtil.jdbcConn;
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      Statement stmt = conn.createStatement();
      PreparedStatement pstmt1 = conn
          .prepareStatement("insert into testtable values(?,?,?,?)");
      for (int i = 1; i < 10; ++i) {
        pstmt1.setInt(1, i);
        pstmt1.setString(2, "desc" + i);
        pstmt1.setString(3, "Add" + i);
        pstmt1.setInt(4, i);
        assertEquals(1, pstmt1.executeUpdate());
      }
      conn.commit();
      PreparedStatement pstmt2 = conn
          .prepareStatement("update testtable set ID1 = ? where description = ?");
      for (int i = 1; i < 5; ++i) {
        pstmt2.setInt(1, i * 10);
        pstmt2.setString(2, "desc" + i);
        assertEquals(1, pstmt2.executeUpdate());
      }
      conn.commit();
      PreparedStatement pstmt3 = conn
          .prepareStatement("select ID1 from TestTable where description = ?");
      for (int i = 1; i < 5; ++i) {
        pstmt3.setString(1, "desc" + i);
        ResultSet rs = pstmt3.executeQuery();
        assertTrue(rs.next());
        assertEquals(i * 10, rs.getInt(1));
      }
      // Now a delete a row & reinsert the row but with changed data . The
      // deletion should go via bulk op route
      assertEquals(1,
          stmt.executeUpdate("delete from TESTTABLE where ADDRESS = 'Add5'"));
      // Insert a row corresponding to the row deleted with Address as Add5
      pstmt1.setInt(1, 5);
      pstmt1.setString(2, "desc.5");
      pstmt1.setString(3, "Add5");
      pstmt1.setInt(4, 5);
      assertEquals(1, pstmt1.executeUpdate());
      conn.commit();
      // PK delete
      stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY);
      conn.commit();
      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    } finally {
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testBug42706_1() throws Exception {

    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2)  replicate ");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      String schema = ((EmbedConnection)TestUtil.jdbcConn)
          .getLanguageConnection().getCurrentSchemaName();
      if (schema == null) {
        schema = Misc.getDefaultSchemaName(((EmbedConnection)TestUtil.jdbcConn)
            .getLanguageConnection());
      }
      conn.createStatement();

      GfxdCallbacksTest.addLoader(schema, "TESTTABLE",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$GfxdTestRowLoader",
          "");
      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 1", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 2", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 3", null, null);

      SerializableCallable queueChecker = getEmptyQueueChecker();
      Boolean retVal = (Boolean)serverExecute(1, queueChecker);
      assertTrue(retVal.booleanValue());

      retVal = (Boolean)serverExecute(2, queueChecker);
      assertTrue(retVal.booleanValue());

      ResultSet rs = derbyStmt.executeQuery("select * from testtable ");
      assertFalse(rs.next());

    } finally {
      ok = false;
      derbyStmt.executeUpdate("delete from testtable");
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testBug42706_2() throws Exception {

    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1, "create table TESTTABLE "
          + "(ID int not null primary key, DESCRIPTION varchar(1024), "
          + "ADDRESS varchar(1024), ID1 int ) partition by column(ID1) "
          + "AsyncEventListener (WBCL2)   ");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      String schema = ((EmbedConnection)TestUtil.jdbcConn)
          .getLanguageConnection().getCurrentSchemaName();
      if (schema == null) {
        schema = Misc.getDefaultSchemaName(((EmbedConnection)TestUtil.jdbcConn)
            .getLanguageConnection());
      }
      conn.createStatement();

      GfxdCallbacksTest.addLoader(schema, "TESTTABLE",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$GfxdTestRowLoader",
          "");
      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 1", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 2", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 3", null, null);

      SerializableCallable queueChecker = getEmptyQueueChecker();
      Boolean retVal = (Boolean)serverExecute(1, queueChecker);
      assertTrue(retVal.booleanValue());

      retVal = (Boolean)serverExecute(2, queueChecker);
      assertTrue(retVal.booleanValue());

      ResultSet rs = derbyStmt.executeQuery("select * from testtable ");
      assertFalse(rs.next());

    } finally {
      ok = false;
      if (derbyStmt != null) {
        derbyStmt.executeUpdate("delete from testtable");
        cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
            new String[] { "TESTTABLE" }, derbyConn);
      }
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testBug42706_3() throws Exception {

    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2)   ");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      String schema = ((EmbedConnection)TestUtil.jdbcConn)
          .getLanguageConnection().getCurrentSchemaName();
      if (schema == null) {
        schema = Misc.getDefaultSchemaName(((EmbedConnection)TestUtil.jdbcConn)
            .getLanguageConnection());
      }
      conn.createStatement();

      GfxdCallbacksTest.addLoader(schema, "TESTTABLE",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$GfxdTestRowLoader",
          "");
      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 1", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 2", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 3", null, null);

      SerializableCallable queueChecker = getEmptyQueueChecker();
      Boolean retVal = (Boolean)serverExecute(1, queueChecker);
      assertTrue(retVal.booleanValue());

      retVal = (Boolean)serverExecute(2, queueChecker);
      assertTrue(retVal.booleanValue());

      ResultSet rs = derbyStmt.executeQuery("select * from testtable ");
      assertFalse(rs.next());

    } finally {
      ok = false;
      derbyStmt.executeUpdate("delete from testtable");
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testTransactionalBehaviourOfBug42706_1() throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2)  replicate ");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      String schema = ((EmbedConnection)TestUtil.jdbcConn)
          .getLanguageConnection().getCurrentSchemaName();
      if (schema == null) {
        schema = Misc.getDefaultSchemaName(((EmbedConnection)TestUtil.jdbcConn)
            .getLanguageConnection());
      }

      GfxdCallbacksTest.addLoader(schema, "TESTTABLE",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$GfxdTestRowLoader",
          "");

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.createStatement();

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 1", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 2", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 3", null, null);

      conn.commit();

      SerializableCallable queueChecker = getEmptyQueueChecker();
      Boolean retVal = (Boolean)serverExecute(1, queueChecker);
      assertTrue(retVal.booleanValue());

      retVal = (Boolean)serverExecute(2, queueChecker);
      assertTrue(retVal.booleanValue());

      ResultSet rs = derbyStmt.executeQuery("select * from testtable ");
      assertFalse(rs.next());

    } finally {
      ok = false;
      derbyStmt.executeUpdate("delete from testtable");
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testTransactionalBehaviourOfBug42706_2() throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2)  ");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      String schema = ((EmbedConnection)TestUtil.jdbcConn)
          .getLanguageConnection().getCurrentSchemaName();
      if (schema == null) {
        schema = Misc.getDefaultSchemaName(((EmbedConnection)TestUtil.jdbcConn)
            .getLanguageConnection());
      }

      GfxdCallbacksTest.addLoader(schema, "TESTTABLE",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$GfxdTestRowLoader",
          "");

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.createStatement();

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 1", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 2", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 3", null, null);

      conn.commit();

      SerializableCallable queueChecker = getEmptyQueueChecker();
      Boolean retVal = (Boolean)serverExecute(1, queueChecker);
      assertTrue(retVal.booleanValue());

      retVal = (Boolean)serverExecute(2, queueChecker);
      assertTrue(retVal.booleanValue());

      ResultSet rs = derbyStmt.executeQuery("select * from testtable ");
      assertFalse(rs.next());

    } finally {
      ok = false;
      derbyStmt.executeUpdate("delete from testtable");
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testTransactionalBehaviourOfBug42706_3() throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      startServerVMs(2, -1, "SG1");

      // create table
      serverSQLExecute(1, "create table TESTTABLE "
          + "(ID int not null primary key, DESCRIPTION varchar(1024), "
          + "ADDRESS varchar(1024), ID1 int) partition by column(ID1)"
          + "AsyncEventListener (WBCL2)");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      startClientVMs(1, 0, null);
      Connection conn = TestUtil.jdbcConn;
      String schema = ((EmbedConnection)TestUtil.jdbcConn)
          .getLanguageConnection().getCurrentSchemaName();
      if (schema == null) {
        schema = Misc.getDefaultSchemaName(((EmbedConnection)TestUtil.jdbcConn)
            .getLanguageConnection());
      }

      GfxdCallbacksTest.addLoader(schema, "TESTTABLE",
          "com.pivotal.gemfirexd.dbsync.DBSynchronizerTestBase$GfxdTestRowLoader",
          "");

      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.createStatement();

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 1", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 2", null, null);

      sqlExecuteVerify(new int[] { 1 }, null,
          "select ID from TESTTABLE where ID = 3", null, null);

      conn.commit();

      SerializableCallable queueChecker = getEmptyQueueChecker();
      Boolean retVal = (Boolean)serverExecute(1, queueChecker);
      assertTrue(retVal.booleanValue());

      retVal = (Boolean)serverExecute(2, queueChecker);
      assertTrue(retVal.booleanValue());

      ResultSet rs = derbyStmt.executeQuery("select * from testtable ");
      assertFalse(rs.next());

    } finally {
      ok = false;
      derbyStmt.executeUpdate("delete from testtable");
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      if (tablesCreated) {
        clientSQLExecute(1, "drop table TESTTABLE");
        // derbyStmt.execute("drop procedure validateTestEnd");
      }
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testSkipListenerBehaviourForDBSynchronizerReplicate()
      throws Exception {
    this.skipListenerforDBSynchronizer(true, false, -1);
  }

  public void testSkipListenerBehaviourForDBSynchronizerPR() throws Exception {
    this.skipListenerforDBSynchronizer(false, false, -1);
  }

  public void testSkipListenerBehaviourForDBSynchronizerReplicateUsingNetConnection()
      throws Exception {
    this.skipListenerforDBSynchronizer(true, true, 2724);
  }

  public void testSkipListenerBehaviourForDBSynchronizerPRUsingNetConnection()
      throws Exception {
    this.skipListenerforDBSynchronizer(false, true, 2725);
  }

  private void skipListenerforDBSynchronizer(boolean useReplicate,
      boolean useNetConnection, int port) throws Exception {

    Statement dStmt = null;
    Connection derbyConn = null;
    NetworkServerControl server = null;
    try {
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      final Statement derbyStmt = derbyConn.createStatement();
      dStmt = derbyStmt;
      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(3, -1, "SG1");
      // create table
      clientSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key, "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int) "
              + "AsyncEventListener (WBCL1) "
              + (useReplicate ? " replicate " : ""));

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);

      // Do an insert in sql fabric, which should also go in DB synchronizer
      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      clientSQLExecute(1, "Insert into TESTTABLE values(2,'desc2','Add2',2)");
      clientSQLExecute(1, "Insert into TESTTABLE values(3,'desc3','Add3',3)");
      if (useNetConnection) {
        TestUtil.startNetServer(port, null);
      }
      Properties props = new Properties();
      props.put(com.pivotal.gemfirexd.Attribute.SKIP_LISTENERS, "true");
      Connection conn = useNetConnection ? TestUtil.getNetConnection(port,
          null, props) : TestUtil.getConnection(props);
      Statement stmt = conn.createStatement();
      // Do PK inserts
      stmt.execute("Insert into TESTTABLE values(4,'desc4','Add4',4)");
      stmt.execute("Insert into TESTTABLE values(5,'desc5','Add5',5)");
      stmt.execute("Insert into TESTTABLE values(6,'desc6','Add6',6)");

      // Do bulk update
      stmt.execute("update TESTTABLE set ID1 = ID1 +1 ");

      // Do PK update
      stmt.execute("update TESTTABLE set ID1 =100 where ID = 1");

      // Bulk Delete
      stmt.execute("delete from testtable ");

      // PK based delete
      stmt.execute("delete from testtable where ID = 2");
      pause(2000);
      stopAsyncEventListener("WBCL1").run();
      waitForCriterion(new WaitCriterion() {
        private int numItems;

        @Override
        public boolean done() {
          try {
            ResultSet rs = derbyStmt.executeQuery("select * from testtable order by ID asc");
            this.numItems = 1;
            while (rs.next()) {
              assertEquals(rs.getInt(1), this.numItems);
              assertEquals(rs.getString(2), "desc" + this.numItems);
              assertEquals(rs.getString(3), "Add" + this.numItems);
              assertEquals(rs.getInt(4), this.numItems);
              this.numItems++;
            }
            return this.numItems == 4;
          } catch (SQLException sqle) {
            fail("unexpected SQLException", sqle);
            // never reached
            return false;
          }
        }

        @Override
        public String description() {
          return "number of items in testtable should be 4 but got "
              + this.numItems;
        }
      }, 60000, 500, true);
    } finally {
      cleanDerbyArtifacts(dStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
      if (useNetConnection) {
        TestUtil.stopNetServer();
      }

    }

  }

  public void testPreventBulkLoadDBSynchInvocationBug42706_1() throws Exception {
    // Start 1 controller , 2 server VMs. Insert/update data.
    // Verify it has reached DBSynchronizer. Now stop the DBSynchronizer.
    // Then add some new data.
    // bring up a new VM. Add some more data.
    // Restart DBSynchronizer.
    // The data added in the interval should not show up in DBSynchronizer.
    // Add a new member. Add some more data . It should show up in DB
    // Synchronzier.

    Connection derbyConn = null;
    Statement derbyStmt = null;
    Statement stmt = null;
    NetworkServerControl server = null;
    boolean tablesCreated = false;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      // create schema

      startServerVMs(3, -1, "SG1");
      startClientVMs(1, 0, null);
      // create table
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      serverSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int ) "
              + "AsyncEventListener (WBCL2) "
              + " partition by range (ID) (VALUES BETWEEN 0 AND 5,  "
              + "VALUES BETWEEN 5  AND 10 , VALUES BETWEEN 10  AND 20 )");

      tablesCreated = true;

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.TRUE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      serverExecute(1, runnable);
      Runnable startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);

      Connection conn = TestUtil.jdbcConn;
      stmt = conn.createStatement();
      String str = "insert into TESTTABLE values";
      for (int i = 0; i < 20; ++i) {
        str += "(" + i + ", 'First', 'J 604'," + i + "),";
      }
      str = str.substring(0, str.length() - 1);
      stmt.executeUpdate(str);
      // TODO Yogesh, this needs to enable once we get support from starting a
      // stopped gateway sender from GFE side
      // Stop DB Synchronizer
      // Runnable stopWBCL = getExecutorToStopWBCL("WBCL2");
      // clientExecute(1, stopWBCL);

      str = "insert into TESTTABLE values";
      for (int i = 20; i < 30; ++i) {
        str += "(" + i + ", 'First', 'J 604'," + i + "),";
      }
      str = str.substring(0, str.length() - 1);
      stmt.executeUpdate(str);
      // startServerVMs(1, -1, "SG1");

      str = "insert into TESTTABLE values";
      for (int i = 30; i < 40; ++i) {
        str += "(" + i + ", 'First', 'J 604'," + i + "),";
      }
      str = str.substring(0, str.length() - 1);
      stmt.executeUpdate(str);
      addExpectedException(null, new int[] { 1, 2, 3 },
          java.sql.SQLIntegrityConstraintViolationException.class);
      startWBCL = startAsyncEventListener("WBCL2");
      serverExecute(1, startWBCL);
      assertEquals(1,
          stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY));
      getServerVM(1).invoke(DBSynchronizerTestBase.class,
          "waitForAsyncQueueFlush", new Object[] { "WBCL2" });
      getServerVM(2).invoke(DBSynchronizerTestBase.class,
          "waitForAsyncQueueFlush", new Object[] { "WBCL2" });
      getServerVM(3).invoke(DBSynchronizerTestBase.class,
          "waitForAsyncQueueFlush", new Object[] { "WBCL2" });
      blockForValidation();
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      derbyStmt = derbyConn.createStatement();
      validateResults(derbyStmt, "select * from testtable where ID < 20",
          this.netPort, true);
      ResultSet rs = derbyStmt
          .executeQuery("select * from TESTTABLE where ID >= 20 and ID <=39");
      // TODO Yogesh, this needs to enable once we get support from starting a
      // stopped gateway sender from GFE side
      // assertFalse(rs.next());
      rs = stmt
          .executeQuery("select count(*) from TESTTABLE where ID >= 20 and ID <=39");
      rs.next();
      assertEquals(rs.getInt(1), 20);
      removeExpectedException(null, new int[] { 1, 2, 3 },
          java.sql.SQLIntegrityConstraintViolationException.class);
    } finally {
      ok = false;
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE" }, derbyConn);
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testDBSynchronizerAfterTruncateTable_Partition() throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(1, -1, "SG1");
      // create table
      clientSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int )"
              + " partition by primary key AsyncEventListener (WBCL1) ");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      Connection conn = TestUtil.jdbcConn;
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Statement stmt = conn.createStatement();
      // Do an insert in sql fabric. This will create a primary bucket on the
      // lone server VM
      // with bucket ID =1

      stmt.executeUpdate("Insert into TESTTABLE values(1114,'desc114','Add114',114)");
      stmt.executeUpdate("Insert into TESTTABLE values(11,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(1227,'desc227','Add227',227)");
      stmt.executeUpdate("Insert into TESTTABLE values(1340,'desc340','Add340',340)");
      conn.commit();

      stmt.executeUpdate("truncate table TESTTABLE");
      conn.commit();
      // assert empty table
      ResultSet rs = stmt.executeQuery("select count(*) from TESTTABLE");
      JDBC.assertSingleValueResultSet(rs, "0");

      // delete everything in GemFireXD again, because truncate table as DDL won't
      // be propagated to Derby
      // However, the following delete as DML will be propagated to Derby to do
      // something equivalent to truncate table
      stmt.executeUpdate("delete from TESTTABLE");

      // Insert some more rows in gemfirexd
      stmt.executeUpdate("Insert into TESTTABLE values(114,'desc114','Add114',114)");
      stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(2,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(224,'desc227','Add227',227)");
      stmt.executeUpdate("Insert into TESTTABLE values(331,'desc340','Add340',340)");
      conn.commit();
      // Bulk Update
      stmt.executeUpdate("update TESTTABLE set ID1 = ID1 +1 ");
      conn.rollback();
      // PK delete
      stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY);
      conn.commit();

      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    } finally {
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  public void testDBSynchronizerWithIdentity_UseCase9() throws Exception {
    // Disabled until this works with transactions. Bug #51603
    if (isTransactional) {
      return;
    }
    Statement derbyStmt = null;
    Connection derbyConn = null;
    NetworkServerControl server = null;
    try {
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();
      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(3, -1, "SG1");
      // create tables
      clientSQLExecute(1, "create table TESTTABLE (ID int "
          + "GENERATED BY DEFAULT AS IDENTITY primary key,"
          + "DESCRIPTION varchar(1024),ADDRESS varchar(1024),ID1 int) "
          + "server groups(SG1) redundancy 1");
      clientSQLExecute(1, "create table TESTTABLE2 (ID int "
          + "GENERATED BY DEFAULT AS IDENTITY primary key,"
          + "DESCRIPTION varchar(1024),ADDRESS varchar(1024),ID1 int) "
          + "server groups(SG1) redundancy 1");
      clientSQLExecute(1,
          "alter table TESTTABLE set AsyncEventListener (WBCL1)");
      clientSQLExecute(1,
          "alter table TESTTABLE2 set AsyncEventListener (WBCL2)");

      derbyStmt.execute("create table TESTTABLE2 (ID int "
          + "GENERATED BY DEFAULT AS IDENTITY primary key,"
          + "DESCRIPTION varchar(1024),ADDRESS varchar(1024),ID1 int)");
      derbyConn.commit();

      String dbsyncDerbyUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true" ;
      if (TestUtil.currentUserName != null) {
        dbsyncDerbyUrl += (",user=" + TestUtil.currentUserName + ",password="
            + TestUtil.currentUserPassword + ',');
      }
      
      // Create first DBSynchronizer with skipIdentityColumns=false
      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", dbsyncDerbyUrl
              + ",app,app,skipIdentityColumns=false", true, Integer.valueOf(1),
          null, Boolean.FALSE, null, null, null, 100000, "", false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      
   // Create second DBSynchronizer without skipIdentityColumns, default is true
      runnable = getExecutorForWBCLConfiguration("SG1", "WBCL2",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", dbsyncDerbyUrl + ",app,app", true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
          "", false);
      runnable.run();
      startWBCL = startAsyncEventListener("WBCL2");
      clientExecute(1, startWBCL);
      pause(5000);

      // Do inserts in GemFireXD
      clientSQLExecute(1, "Insert into TESTTABLE values(1,'desc1','Add1',1)");
      clientSQLExecute(1, "Insert into TESTTABLE values(2,'desc2','Add2',2)");
      clientSQLExecute(1, "Insert into TESTTABLE(DESCRIPTION, ADDRESS, ID1) "
          + "values('desc3','Add3',3)");
      clientSQLExecute(1, "Insert into TESTTABLE(DESCRIPTION, ADDRESS, ID1) "
          + "values('desc4','Add4',4)");
      clientSQLExecute(1, "Insert into TESTTABLE(DESCRIPTION, ADDRESS, ID1) "
          + "values('desc5','Add5',5)");
      clientSQLExecute(1, "Insert into TESTTABLE(DESCRIPTION, ADDRESS, ID1) "
          + "values('desc6','Add6',6)");
      // Bulk inserts
      PreparedStatement pstmt = TestUtil.getPreparedStatement("insert into "
          + "TESTTABLE (DESCRIPTION, ADDRESS, ID1) values (?, ?, ?)");
      for (int i = 7; i < 30; i++) {
        pstmt.setString(1, "desc" + i);
        pstmt.setString(2, "Addr" + i);
        pstmt.setInt(3, i);
        pstmt.addBatch();
      }
      assertEquals(23, pstmt.executeBatch().length);
      // Bulk Update
      clientSQLExecute(1, "update TESTTABLE set ID1 = ID1 +1 ");

      // Bulk Delete
      clientSQLExecute(1, "delete from testtable where ADDRESS = 'Add5'");
      clientSQLExecute(1, "delete from TESTTABLE where ID = " + DELETED_KEY);

      // now operations on TESTTABLE2
      clientSQLExecute(1, "Insert into TESTTABLE2 values(1,'desc1','Add1',1)");
      clientSQLExecute(1, "Insert into TESTTABLE2 values(2,'desc2','Add2',2)");
      clientSQLExecute(1, "Insert into TESTTABLE2(DESCRIPTION, ADDRESS, ID1) "
          + "values('desc3','Add3',3)");
      clientSQLExecute(1, "Insert into TESTTABLE2(DESCRIPTION, ADDRESS, ID1) "
          + "values('desc4','Add4',4)");
      clientSQLExecute(1, "Insert into TESTTABLE2(DESCRIPTION, ADDRESS, ID1) "
          + "values('desc5','Add5',5)");
      clientSQLExecute(1, "Insert into TESTTABLE2(DESCRIPTION, ADDRESS, ID1) "
          + "values('desc6','Add6',6)");
      // Bulk inserts
      pstmt = TestUtil.getPreparedStatement("insert into "
          + "TESTTABLE2 (DESCRIPTION, ADDRESS, ID1) values (?, ?, ?)");
      for (int i = 7; i < 30; i++) {
        pstmt.setString(1, "desc" + i);
        pstmt.setString(2, "Addr" + i);
        pstmt.setInt(3, i);
        pstmt.addBatch();
      }
      assertEquals(23, pstmt.executeBatch().length);
      // Bulk Update
      clientSQLExecute(1, "update TESTTABLE2 set ID1 = ID1 +1 ");

      // Bulk Delete
      clientSQLExecute(1, "delete from testtable2 where ADDRESS = 'Add5'");
      clientSQLExecute(1, "delete from TESTTABLE2 where ID = " + DELETED_KEY);

      blockForValidation();

      stopAsyncEventListener("WBCL1").run();
      stopAsyncEventListener("WBCL2").run();
      Thread.sleep(5000);

      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
      validateResults(derbyStmt, "select * from testtable2", this.netPort, true);
    } finally {
      cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
          new String[] { "TESTTABLE", "TESTTABLE2" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  /**
   * NOTE:
   * This test is not valid when 'enableBULKDMLStr = false'. 
   * Added a new test in SerialDBSynchronizerDUnit for the same scenario that 
   * works with bulkDML disabled.
   * Commenting this test. If need to have tests for bulkDML, then it can be 
   * ported to a new DUnit test class which will set the enableBULKDMLStr to 
   * true before running the tests. 
   */
  public void _testDBSynchronizerAfterTruncateTable_Replicate()
      throws Exception {
    Connection derbyConn = null;
    Statement derbyStmt = null;
    NetworkServerControl server = null;
    try {
      server = startNetworkServer();
      createDerbyValidationArtefacts();
      String derbyDbUrl = "jdbc:derby://localhost:" + this.netPort
          + "/newDB;create=true;";
      if (TestUtil.currentUserName != null) {
        derbyDbUrl += ("user=" + TestUtil.currentUserName + ";password="
            + TestUtil.currentUserPassword + ';');
      }
      derbyConn = DriverManager.getConnection(derbyDbUrl);
      // create schema
      derbyStmt = derbyConn.createStatement();

      // Create the controller VM as client which belongs to default server
      // group
      startClientVMs(1, 0, null);
      startServerVMs(1, -1, "SG1");
      // create table
      clientSQLExecute(1,
          "create table TESTTABLE (ID int not null primary key , "
              + "DESCRIPTION varchar(1024) , ADDRESS varchar(1024), ID1 int )"
              + " replicate AsyncEventListener (WBCL1) ");

      Runnable runnable = getExecutorForWBCLConfiguration("SG1", "WBCL1",
          "com.pivotal.gemfirexd.callbacks.DBSynchronizer",
          "org.apache.derby.jdbc.ClientDriver", derbyDbUrl, true,
          Integer.valueOf(1), null, Boolean.FALSE, null, null, null, 100000,
          "org.apache.derby.jdbc.ClientDriver," + derbyDbUrl, false);
      runnable.run();
      Runnable startWBCL = startAsyncEventListener("WBCL1");
      clientExecute(1, startWBCL);
      Connection conn = TestUtil.jdbcConn;
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Statement stmt = conn.createStatement();
      // Do an insert in sql fabric. This will create a primary bucket on the
      // lone server VM
      // with bucket ID =1

      stmt.executeUpdate("Insert into TESTTABLE values(1114,'desc114','Add114',114)");
      stmt.executeUpdate("Insert into TESTTABLE values(11,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(1227,'desc227','Add227',227)");
      stmt.executeUpdate("Insert into TESTTABLE values(1340,'desc340','Add340',340)");
      conn.commit();

      stmt.executeUpdate("truncate table TESTTABLE");
      conn.commit();
      // assert empty table
      ResultSet rs = stmt.executeQuery("select count(*) from TESTTABLE");
      JDBC.assertSingleValueResultSet(rs, "0");

      // delete everything in GemFireXD again, because truncate table as DDL won't
      // be propagated to Derby
      // However, the following delete as DML will be propagated to Derby to do
      // something equivalent to truncate table
      stmt.executeUpdate("delete from TESTTABLE");

      // Insert some more rows in gemfirexd
      stmt.executeUpdate("Insert into TESTTABLE values(114,'desc114','Add114',114)");
      stmt.executeUpdate("Insert into TESTTABLE values(1,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(2,'desc1','Add1',1)");
      stmt.executeUpdate("Insert into TESTTABLE values(224,'desc227','Add227',227)");
      stmt.executeUpdate("Insert into TESTTABLE values(331,'desc340','Add340',340)");
      conn.commit();
      // Bulk Update
      stmt.executeUpdate("update TESTTABLE set ID1 = ID1 +1 ");
      conn.rollback();
      // PK delete
      stmt.executeUpdate("delete from TESTTABLE where ID = " + DELETED_KEY);
      conn.commit();

      blockForValidation();
      stopAsyncEventListener("WBCL1").run();
      validateResults(derbyStmt, "select * from testtable", this.netPort, true);
    } finally {
      cleanDerbyArtifacts(derbyStmt, new String[] { "validateTestEnd" },
          new String[] { "test_ok" }, new String[] { "TESTTABLE" }, derbyConn);
      // might get ShutdownExceptions in derby connection close
      addExpectedDerbyException(ShutdownException.class.getName());
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (!sqle.getMessage().contains("shutdown")) {
          sqle.printStackTrace();
        }
      }
      if (server != null) {
        server.shutdown();
      }
    }
  }

  private SerializableCallable getEmptyQueueChecker() {
    SerializableCallable queueChecker = new SerializableCallable(
        "Check For Empty Queue") {
      @Override
      public Object call() throws CacheException {
        final GemFireCacheImpl cache = Misc.getGemFireCache();
        Boolean qEmpty = Boolean.TRUE;
        final Set<GatewaySender> senders = cache.getAllGatewaySenders();
        if (senders.isEmpty()) {
          return Boolean.TRUE;
        }
        for (GatewaySender sender : senders) {
          qEmpty = (((AbstractGatewaySender)sender).getQueue().size() == 0);
          if (!qEmpty.booleanValue()) {
            break;
          }
        }
        return qEmpty;
      }
    };
    return queueChecker;
  }

  public static class TXAsyncEventListener implements AsyncEventListener {

    private Connection conn;

    private final AsyncEventHelper helper = new AsyncEventHelper();

    /**
     * {@inheritDoc}
     */
    @Override
    public void init(String initParamStr) {
      try {
        this.conn = DriverManager.getConnection(initParamStr);
      } catch (SQLException sqle) {
        throw new GemFireXDRuntimeException(sqle);
      }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void start() {
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean processEvents(List<Event> events) {
      try {
        for (Event ev : events) {
          if (ev.getType().isBulkInsert()) {
            ResultSet rows = ev.getNewRowsAsResultSet();
            TableMetaData metaData = ev.getResultSetMetaData();
            int numCols = metaData.getColumnCount();
            String insertStr = AsyncEventHelper.getInsertString(
                ev.getTableName(), metaData, false);
            PreparedStatement ps = this.conn.prepareStatement(insertStr);
            int numRows = 0;
            while (rows.next()) {
              for (int i = 1; i <= numCols; i++) {
                ps.setObject(i, rows.getObject(i));
              }
              ps.addBatch();
              numRows++;
            }
            int[] results = ps.executeBatch();
            assertEquals(numRows, results.length);
            for (int r : results) {
              assertEquals(1, r);
            }
          }
          else if (ev.getType().isBulkOperation()) {
            PreparedStatement ps = this.conn
                .prepareStatement(ev.getDMLString());
            helper.setParamsInBulkPreparedStatement(ev, ev.getType(), ps, null,
                null);
            ps.execute();
          }
        }
      } catch (SQLException sqle) {
        throw new GemFireXDRuntimeException(sqle);
      }
      return true;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
      try {
        if (this.conn != null) {
          this.conn.close();
        }
      } catch (SQLException sqle) {
        throw new GemFireXDRuntimeException(sqle);
      }
    }
  }
}
