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

package com.pivotal.gemfirexd.callbacks;

import java.io.FileInputStream;
import java.sql.BatchUpdateException;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.SQLIntegrityConstraintViolationException;
import java.sql.SQLNonTransientConnectionException;
import java.sql.SQLNonTransientException;
import java.sql.SQLTransientException;
import java.sql.Statement;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.regex.Pattern;
import java.util.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.cache.RegionDestroyedException;
import com.pivotal.gemfirexd.execute.QueryObserver;
import com.pivotal.gemfirexd.execute.QueryObserverHolder;

/**
 * DBSynchronizer class instance persists the GemFireXD operations to an external
 * database having a JDBC driver.
 */
public class DBSynchronizer implements AsyncEventListener {

  /** the JDBC connection URL for the external database */
  protected String dbUrl;

  /** user name to use for establishing JDBC connection */
  protected String userName;

  /** password for the user to use for establishing JDBC connection */
  protected String passwd;

  /** file to write errors */
  protected String errorFile;
  
  /** number of retries after an error before dumping to {@link #errorFile} */
  protected int numErrorTries = 0;
  
  /**
   * default number of retries after an error is 3 when {@link #errorFile} is
   * defined
   */
  protected static final int DEFAULT_ERROR_TRIES = 3;
  
  /**
   * the transformation (e.g. AES/ECB/PKCS5Padding or Blowfish) to use for
   * encrypting the password
   */
  protected String transformation;

  /** the key size of the algorithm being used for encrypted the password */
  protected int keySize;

  /**
   * fully qualified name of the implementation class of the JDBC {@link Driver}
   * interface
   */
  protected String driverClass;

  /**
   * the {@link Driver} implementation instance loaded using the
   * {@link #driverClass}
   */
  protected Driver driver;

  /**
   * the current JDBC connection being used by this instance
   */
  protected Connection conn;

  /**
   * Flag to indicate whether the current {@link #driver} is JDBC4 compliant for
   * BLOB/CLOB related API. Value can be one of {@link #JDBC4DRIVER_UNKNOWN},
   * {@link #JDBC4DRIVER_FALSE} or {@link #JDBC4DRIVER_TRUE}.
   */
  private byte isJDBC4Driver = JDBC4DRIVER_UNKNOWN;

  /**
   * if it is unknown whether the driver is JDBC4 compliant for BLOB/CLOB
   * related API
   */
  protected static final byte JDBC4DRIVER_UNKNOWN = -1;
  /**
   * if it is known that the driver is not JDBC4 compliant for BLOB/CLOB related
   * API
   */
  protected static final byte JDBC4DRIVER_FALSE = 0;
  /**
   * if it is known that the driver is JDBC4 compliant for BLOB/CLOB related API
   */
  protected static final byte JDBC4DRIVER_TRUE = 1;

  /** true if this instance has been closed or not initialized */
  protected volatile boolean shutDown = true;

  protected final AsyncEventHelper helper = AsyncEventHelper.newInstance();

  protected final Logger logger = Logger
      .getLogger(AsyncEventHelper.LOGGER_NAME);

  /**
   * true if "TraceDBSynchronizer" debug flag is enabled in this GemFireXD
   * instance (via gemfirexd.debug.true=TraceDBSynchronizer).
   */
  protected boolean traceDBSynchronizer = helper.traceDBSynchronizer()
      && logger.isLoggable(Level.INFO);
  /**
   * true if "TraceDBSynchronizer" debug flag is enabled in this GemFireXD
   * instance (via gemfirexd.debug.true=TraceDBSynchronizer).
   */
  protected boolean traceDBSynchronizerHA = helper.traceDBSynchronizerHA()
      && logger.isLoggable(Level.INFO);
  
  /** the cached {@link PreparedStatement}s for inserts */
  protected final HashMap<String, PreparedStatement> insertStmntMap =
      new HashMap<String, PreparedStatement>();

  /** the cached {@link PreparedStatement}s for primary key based updates */
  protected final HashMap<String, PreparedStatement> updtStmntMap =
      new HashMap<String, PreparedStatement>();

  /** the cached {@link PreparedStatement}s for primary key based deletes */
  protected final HashMap<String, PreparedStatement> deleteStmntMap =
      new HashMap<String, PreparedStatement>();

  /** the map of table name to its current metadata object */
  protected final HashMap<String, TableMetaData> metadataMap =
      new HashMap<String, TableMetaData>();

  /** the map of table name to its current primary key metadata object */
  protected final HashMap<String, ResultSetMetaData> pkMetadataMap =
      new HashMap<String, ResultSetMetaData>();

  /**
   * the cached {@link PreparedStatement}s for other non-primary key based DML
   * operations
   */
  protected final HashMap<String, PreparedStatement> bulkOpStmntMap =
      new HashMap<String, PreparedStatement>();
  
  /**
   * if true then skip identity columns in sync else also set the values of
   * identity columns as in GemFireXD
   */
  protected boolean skipIdentityColumns = true;

  // keys used in external property file
  protected static final String DBDRIVER = "driver";
  protected static final String DBURL = "url";
  protected static final String USER = "user";
  protected static final String PASSWORD = "password";
  protected static final String SECRET = "secret";
  protected static final String TRANSFORMATION = "transformation";
  protected static final String KEYSIZE = "keysize";

  protected static final String ERRORFILE = "errorfile";
  protected static final String ERRORTRIES = "errortries";
  protected static final String SKIP_IDENTITY_COLUMNS = "skipIdentityColumns"; 

  // Log strings
  protected static final String Gfxd_DB_SYNCHRONIZER__1 = "DBSynchronizer::"
      + "processEvents: Exception while fetching prepared statement "
      + "for event '%s': %s";
  protected static final String Gfxd_DB_SYNCHRONIZER__2 = "DBSynchronizer::"
      + "processEvents: Unexpected Exception occured while processing "
      + "Events. The list of unprocessed events is: %s. "
      + "Attempt will be made to rollback the changes.";
  protected static final String Gfxd_DB_SYNCHRONIZER__3 = "DBSynchronizer::"
      + "processEvents: Operation failed for event '%s' "
      + "due to exception: %s";
  protected static final String Gfxd_DB_SYNCHRONIZER__4 = "DBSynchronizer::"
      + "closeStatements: Exception in closing prepared statement "
      + "with DML string: %s";
  protected static final String Gfxd_DB_SYNCHRONIZER__5 = "DBSynchronizer::"
      + "close: Exception in closing SQL Connection: %s";
  protected static final String Gfxd_DB_SYNCHRONIZER__6 = "DBSynchronizer::"
      + "init: Exception while initializing connection for driver class '%s' "
      + "and db url = %s";
  protected static final String Gfxd_DB_SYNCHRONIZER__7 = "DBSynchronizer::"
      + "processEvents: Exception occured while committing '%s' "
      + "to external DB: %s";
  protected static final String Gfxd_DB_SYNCHRONIZER__8 = "DBSynchronizer::init"
      + ": Illegal format of init string '%s', expected <driver>,<URL>,...";
  protected static final String Gfxd_DB_SYNCHRONIZER__9 = "DBSynchronizer::"
      + "init: Exception in loading properties file '%s' for initialization";
  protected static final String Gfxd_DB_SYNCHRONIZER__10 = "DBSynchronizer::"
      + "init: missing Driver or URL properties in file '%s'";
  protected static final String Gfxd_DB_SYNCHRONIZER__11 = "DBSynchronizer::"
      + "init: unknown property '%s' in file '%s'";
  protected static final String Gfxd_DB_SYNCHRONIZER__12 = "DBSynchronizer::"
      + "init: both password and secret properties specified in file '%s'";
  protected static final String Gfxd_DB_SYNCHRONIZER__13 = "DBSynchronizer::"
      + "init: initialized with URL '%s' using driver class '%s'";

  
  /** Holds event that failed to be applied to underlying database and the time of failure*/
  private static class ErrorEvent implements Comparable {
    Event ev;
    long errortime;
    @Override
    public int compareTo(Object o) {
      ErrorEvent ee = (ErrorEvent)o;
      // If events are equal, nevermind the time, else allow sorting by failure time, earlier first.
      if(ee.ev.equals(this.ev)) {
        return 0;
      } else if (ee.errortime > this.errortime) {
        return -1;
      } else {
        return 1;
      }
    }
  }
  
  /** keeps track of the retries that have been done for an event */
  protected final ConcurrentSkipListMap<ErrorEvent, Object[]> errorTriesMap = new ConcurrentSkipListMap<ErrorEvent, Object[]>();
  
  /**
   * Enumeration that defines the action to be performed in case an exception is
   * received during processing by {@link DBSynchronizer#processEvents(List)}
   */
  protected static enum SqlExceptionHandler {
    /**
     * ignore the exception and continue to process other events in the current
     * batch
     */
    IGNORE {
      @Override
      public void execute(DBSynchronizer synchronizer) {
        // No -op
        synchronizer.logger.info("DBSynchronizer::Ignoring error");
      }

      @Override
      public boolean breakTheLoop() {
        return false;
      }
    },

    /**
     * ignore the exception and break the current batch of events being
     * processed
     */
    IGNORE_BREAK_LOOP {
      @Override
      public boolean breakTheLoop() {
        return true;
      }

      @Override
      public void execute(DBSynchronizer synchronizer) {
        // No -op
      }
    },

    /**
     * create a new database connection since the current one is no longer
     * usable
     */
    REFRESH {
      @Override
      public void execute(DBSynchronizer synchronizer) {
        synchronized (synchronizer) {
          try {
            if (!synchronizer.conn.isClosed()) {
              if (synchronizer.helper.logFineEnabled()) {
                synchronizer.logger.fine("DBSynchronizer::"
                    + "SqlExceptionHandler: before rollback");

              }
              // For safe side just roll back the transaction so far
              synchronizer.conn.rollback();
              if (synchronizer.helper.logFineEnabled()) {
                synchronizer.logger.fine("DBSynchronizer::"
                    + "SqlExceptionHandler: after rollback");
              }
            }
          } catch (SQLException sqle) {
            synchronizer.helper.log(synchronizer.logger, Level.WARNING, sqle,
                "DBSynchronizer::SqlExceptionHandler: "
                    + "could not successfully rollback");
          }
          synchronizer.basicClose();

          if (!synchronizer.shutDown) {
            // Do not recreate the connection in case of shutdown
            synchronizer.logger.info("DBSynchronizer::Attempting to reconnect to database");
            synchronizer.instantiateConnection();
          }
        }
      }
    },

    /** close the current connection */
    CLEANUP {
      @Override
      public void execute(DBSynchronizer synchronizer) {
        synchronized (synchronizer) {

          try {
            if (synchronizer.conn!= null && !synchronizer.conn.isClosed()) {
              if (synchronizer.helper.logFineEnabled()) {
                synchronizer.logger.fine("DBSynchronizer::"
                    + "SqlExceptionHandler: before rollback");
              }
              // For safeside just roll back the transactions so far
              synchronizer.conn.rollback();
              if (synchronizer.helper.logFineEnabled()) {
                synchronizer.logger.fine("DBSynchronizer::"
                    + "SqlExceptionHandler: after rollback");
              }
            }
          } catch (SQLException sqle) {
            synchronizer.helper.log(synchronizer.logger, Level.WARNING, sqle,
                "DBSynchronizer::SqlExceptionHandler: "
                    + "could not successfully rollback");
          }
          synchronizer.basicClose();
        }
      }
    };

    /**
     * execute an action specified by different enumeration values after an
     * unexpected exception is received by
     * {@link DBSynchronizer#processEvents(List)}
     */
    public abstract void execute(DBSynchronizer synchronizer);

    /**
     * Returns true if processing for the current batch of events has to be
     * terminated for the current exception. Default handling is to return true,
     * unless specified otherwise by an enumerated action.
     */
    public boolean breakTheLoop() {
      return true;
    }
  }

  /**
   * Close this {@link DBSynchronizer} instance.
   * 
   * To prevent a possible concurrency issue between closing thread &amp; the
   * processor thread, access to this method is synchronized on 'this'
   */
  public synchronized void close() {
    // Flush any pending error events to XML log
    this.flushErrorEventsToLog();
    this.shutDown = true;
    this.basicClose();
    this.helper.close();
  }

  /**
   * Basic actions to be performed to close the {@link DBSynchronizer} instance
   * though the instance will itself not be marked as having shut down.
   * 
   * To prevent a possible concurrency issue between closing thread &amp; the
   * processor thread, access to this method is synchronized on 'this'
   */
  public final synchronized void basicClose() {
    // I believe that by this time the processor thread has stopped. No harm
    // in checking ppep statement cache clear even if connection is not active.
    closeStatements(this.insertStmntMap);
    closeStatements(this.updtStmntMap);
    closeStatements(this.deleteStmntMap);
    closeStatements(this.bulkOpStmntMap);
    try {
      if (this.conn != null && !this.conn.isClosed()) {
        this.conn.close();
      }
    } catch (SQLException sqle) {
      if (logger.isLoggable(Level.INFO)) {
        helper.logFormat(logger, Level.INFO, sqle, Gfxd_DB_SYNCHRONIZER__5,
            this.conn);
      }
    }
  }

  /**
   * Return {@link #JDBC4DRIVER_TRUE} if this driver is known to be JDBC4
   * compliant for LOB support, {@link #JDBC4DRIVER_FALSE} if it is known to not
   * be JDBC4 compliant and {@link #JDBC4DRIVER_UNKNOWN} if the status is
   * unknown.
   */
  protected final byte isJDBC4Driver() {
    return this.isJDBC4Driver;
  }

  /**
   * Set the JDBC4 status of this driver as per {@link #isJDBC4Driver()}.
   */
  protected final void setJDBC4Driver(byte status) {
    this.isJDBC4Driver = status;
  }

  /*
   * to prevent a possible concurrency issue between closing thread & the
   * processor thread, access to this method is synchronized on 'this'
   */
  protected void closeStatements(Map<String, PreparedStatement> psMap) {
    Iterator<Map.Entry<String, PreparedStatement>> itr = psMap.entrySet()
        .iterator();
    while (itr.hasNext()) {
      Map.Entry<String, PreparedStatement> entry = itr.next();
      try {
        entry.getValue().close();
      } catch (SQLException sqle) {
        if (logger.isLoggable(Level.INFO)) {
          helper.logFormat(logger, Level.INFO, sqle, Gfxd_DB_SYNCHRONIZER__4,
              entry.getKey());
        }
      } finally {
        itr.remove();
      }
    }
  }

  /**
   * Initialize this {@link DBSynchronizer} instance, creating a new JDBC
   * connection to the backend database as per the provided parameter.<BR>
   * 
   * The recommended format of the parameter string is: <BR>
   * 
   * file=&lt;path&gt; <BR>
   * 
   * The file is a properties file specifying the driver, JDBC URL, user and
   * password.<BR>
   * 
   * Driver=&lt;driver-class&gt;<BR>
   * URL=&lt;JDBC URL&gt;<BR>
   * User=&lt;user name&gt;<BR>
   * <BR>
   * Secret=&lt;encrypted password&gt;<BR>
   * Transformation=&lt;transformation for the encryption cipher&gt;<BR>
   * KeySize=&lt;size of the private key to use for encryption&gt;<BR>
   * -- OR --<BR>
   * Password=&lt;password&gt;<BR>
   * 
   * The password provided in the "Secret" property should be an encrypted one
   * generated using the "gfxd encrypt-password external" command, else the
   * "Password" property can be used to specify the password in plain-text. The
   * "Transformation" and "KeySize" properties optionally specify the
   * transformation and key size used for encryption else the defaults are used
   * ("AES" and 128 respectively). User and password are optional and when not
   * provided then JDBC URL will be used as is for connection.
   * 
   * The above properties may also be provided inline like below:<BR>
   * <BR>
   * &lt;driver-class&gt;,&lt;JDBC
   * URL&gt;[,&lt;user&gt;[,&lt;password&gt;|secret
   * =&lt;secret&gt;][,transformation=&lt;transformation&gt;][,keysize=&lt;key
   * size&gt;]<BR>
   * <BR>
   * The user and password parts are optional and can be possibly embedded in
   * the JDBC URL itself. The password can be encrypted one generated using the
   * "gfxd encrypt-password external" command in which case it should be
   * prefixed with "secret=". It can also specify the transformation and keysize
   * using the optional "transformation=..." and "keysize=..." properties.
   */
  public void init(String initParamStr) {
    this.driver = null;
    this.driverClass = null;
    this.dbUrl = null;
    this.userName = null;
    this.passwd = null;
    this.transformation = null;
    this.keySize = 0;
    this.numErrorTries = 0;
    String secret = null;
    // check the new "file=<properties file>" option first
    if (initParamStr.startsWith("file=")) {
      String propsFile = initParamStr.substring("file=".length());
      FileInputStream fis = null;
      final Properties props = new Properties();
      try {
        fis = new FileInputStream(propsFile);
        props.load(fis);
      } catch (Exception e) {
        throw helper.newRuntimeException(
            String.format(Gfxd_DB_SYNCHRONIZER__9, propsFile), e);
      } finally {
        try {
          if (fis != null) {
            fis.close();
          }
        } catch (Exception e) {
          // ignored
        }
      }
      try {
        for (Map.Entry<Object, Object> entry : props.entrySet()) {
          String key = ((String)entry.getKey()).trim();
          String value = ((String)entry.getValue()).trim();
          if (DBDRIVER.equalsIgnoreCase(key)) {
            this.driverClass = value;
          }
          else if (DBURL.equalsIgnoreCase(key)) {
            this.dbUrl = value;
          }
          else if (USER.equalsIgnoreCase(key)) {
            this.userName = value;
          }
          else if (SECRET.equalsIgnoreCase(key)) {
            secret = value;
          }
          else if (TRANSFORMATION.equalsIgnoreCase(key)) {
            this.transformation = value;
          }
          else if (KEYSIZE.equalsIgnoreCase(key)) {
            this.keySize = Integer.parseInt(value);
          }
          else if (PASSWORD.equalsIgnoreCase(key)) {
            this.passwd = value;
          }
          else if (ERRORFILE.equalsIgnoreCase(key)) {
            this.errorFile = value;
          }
          else if (ERRORTRIES.equalsIgnoreCase(key)) {
            this.numErrorTries = Integer.parseInt(value);
          }
          else if (SKIP_IDENTITY_COLUMNS.equalsIgnoreCase(key)) {
            this.skipIdentityColumns = Boolean.parseBoolean(value);
          }
          else {
            throw new IllegalArgumentException(String.format(
                Gfxd_DB_SYNCHRONIZER__11, key, propsFile));
          }
        }
        if (secret != null) {
          if (this.passwd != null) {
            throw new IllegalArgumentException(String.format(
                Gfxd_DB_SYNCHRONIZER__12, propsFile));
          }
          // check that secret is encrypted
          AsyncEventHelper.decryptPassword(this.userName, secret,
              this.transformation, this.keySize);
          this.passwd = secret;
        }
        else if (this.passwd != null) {
          this.passwd = AsyncEventHelper.encryptPassword(this.userName,
              this.passwd, this.transformation, this.keySize);
        }
      } catch (IllegalArgumentException e) {
        throw e;
      } catch (Exception e) {
        throw helper.newRuntimeException(
            String.format(Gfxd_DB_SYNCHRONIZER__9, propsFile), e);
      }
      if (this.driverClass == null || this.driverClass.length() == 0
          || this.dbUrl == null || this.dbUrl.length() == 0) {
        throw new IllegalArgumentException(String.format(
            Gfxd_DB_SYNCHRONIZER__10, propsFile));
      }
    }
    else {
      inlineInit(initParamStr);
    }
    helper.createEventErrorLogger(errorFile);

    this.initConnection();
  }
  
  protected void inlineInit(String initParamStr) {
    logger.info("DBSynchronizer::Inline init parameters:" + initParamStr);
    String[] params = initParamStr.split(",");
    if (params.length < 2) {
      throw new IllegalArgumentException(String.format(Gfxd_DB_SYNCHRONIZER__8,
          initParamStr));
    }
    String password = null;
    String secret = null;
    int paramNo = 1;
    for (String param : params) {
      param = param.trim();
      StringBuilder value = new StringBuilder();
      if (isArgPresent(param, DBDRIVER, value)) {
        this.driverClass = value.toString().trim();
      } else if (isArgPresent(param, DBURL, value)) {
        this.dbUrl = value.toString().trim();
      } else if (isArgPresent(param, USER + '=', value)) {
        this.userName = value.toString().trim();
      } else if (isArgPresent(param, PASSWORD + '=', value)) {
        password = value.toString().trim();
      } else if (isArgPresent(param, TRANSFORMATION + '=', value)) {
        this.transformation = value.toString();
      } else if (isArgPresent(param, KEYSIZE + '=', value)) {
        this.keySize = Integer.parseInt(value.toString());
      } else if (isArgPresent(param, SECRET + '=', value)) {
        secret = value.toString().trim();
      } else if (isArgPresent(param, ERRORFILE + '=', value)) {
        this.errorFile = value.toString().trim();
      } else if (isArgPresent(param, ERRORTRIES + '=', value)) {
        this.numErrorTries = Integer.parseInt(value.toString());
      } else if (isArgPresent(param, SKIP_IDENTITY_COLUMNS + '=', value)) {
        this.skipIdentityColumns = Boolean.parseBoolean(value.toString());
      } else if (paramNo == 1) {
        // Assume this is the driver name
        this.driverClass = param.trim();
      } else if (paramNo == 2) {
        // Assume this is the db url
        this.dbUrl = param.trim();
      } else if (paramNo == 3) {
        // The third param is expected to be username if not explicitly provided.
        this.userName = param.trim();
      } else if (paramNo == 4) {
        this.passwd = param.trim();
      }
      ++paramNo;
    }
    try {
      // First check if the provided password in 'secret' field is really encrypted
      boolean isEncrypted = false;
      try {
        if (secret != null) {
          logger.info("DBSynchronizer::Attempting to decrypt password");
          AsyncEventHelper.decryptPassword(this.userName, secret,
              this.transformation, this.keySize);
          isEncrypted = true;
        }
      } catch (Exception e) {
        logger.info("DBSynchronizer::Exception decrypting password:" + e.getMessage() + "Provided password was probably not encrypted.");
        // ignore
        isEncrypted = false;
      }
      // If encrypted use the value in 'secret' field as the password
      // else encrypt the value in 'password' field and use it as the password
      if (isEncrypted) {
        this.passwd = secret;
      } else {
        // store encrypted
        if (password != null) {
          logger.info("DBSynchronizer::Encrypting the provided password");
          this.passwd = AsyncEventHelper.encryptPassword(this.userName,
              password, this.transformation, this.keySize);
        } else {
          this.passwd = null;
        }
      }
    } catch (Exception e) {
      String maskedPasswdDbUrl = null;
      if (this.dbUrl != null) {
        maskedPasswdDbUrl = maskPassword(this.dbUrl);
      }
      throw helper.newRuntimeException(String.format(
          Gfxd_DB_SYNCHRONIZER__6, this.driverClass, maskedPasswdDbUrl), e);
    }
  }

  protected boolean isArgPresent(String s, String prefix, StringBuilder extracted) {
    if ((s.length() > prefix.length()
        && prefix.equalsIgnoreCase(s.substring(0, prefix.length())))) {
      extracted.append(s.substring(prefix.length()));
      return true;
    }
    return false;
  }
  protected String trimIgnoreCase(String s, String prefix) {
    if (s.length() > prefix.length()
        && prefix.equalsIgnoreCase(s.substring(0, prefix.length()))) {
      return s.substring(prefix.length());
    }
    else {
      return null;
    }
  }

  protected synchronized void initConnection() {
    String maskedPasswordDbUrl = null;
    if (this.dbUrl != null) {
      maskedPasswordDbUrl = maskPassword(this.dbUrl);
    }
    try {
      Class.forName(this.driverClass).newInstance();
      this.driver = DriverManager.getDriver(this.dbUrl);
    } catch (Exception e) {
      throw helper.newRuntimeException(String.format(Gfxd_DB_SYNCHRONIZER__6,
          this.driverClass, maskedPasswordDbUrl), e);
    }
    this.instantiateConnection();
    if (this.logger.isLoggable(Level.INFO)) {
      this.helper.logFormat(this.logger, Level.INFO, null,
          Gfxd_DB_SYNCHRONIZER__13, maskedPasswordDbUrl, this.driverClass);
    }
    this.shutDown = false;
  }

  protected synchronized void instantiateConnection() {
    if (this.driver == null) {
      initConnection();
      return;
    }
    String maskedPasswordDbUrl = null;
    try {
      // use Driver directly for connect instead of looping through all
      // drivers as DriverManager.getConnection() would do, to avoid
      // hitting any broken drivers in the process (vertica driver is known to
      //   fail in acceptsURL with this set of properties)
      final Properties props = new Properties();
      // the user/password property names are standard ones also used by
      // DriverManager.getConnection(String, String, String) itself, so
      // will work for all drivers
      if (this.userName != null) {
        props.put("user", this.userName);
      }
      if (this.passwd != null) {
        // password is now stored encrypted
        String decPasswd = AsyncEventHelper.decryptPassword(this.userName,
            this.passwd, this.transformation, this.keySize);
        props.put("password", decPasswd);
        decPasswd = null;
      }

      this.conn = this.driver.connect(this.dbUrl, props);
      // null to GC password as soon as possible
      props.clear();
      try {
        // try to set the default isolation to at least READ_COMMITTED
        // need it for proper HA handling
        if (this.conn.getTransactionIsolation() < Connection
            .TRANSACTION_READ_COMMITTED && this.conn.getMetaData()
            .supportsTransactionIsolationLevel(
                Connection.TRANSACTION_READ_COMMITTED)) {
          this.conn
              .setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
          if (this.dbUrl != null) {
            maskedPasswordDbUrl = maskPassword(this.dbUrl);
          }
          logger.info("explicitly set the transaction isolation level to "
              + "READ_COMMITTED for URL: " + maskedPasswordDbUrl);
        }
      } catch (SQLException sqle) {
        // ignore any exception here
      }
      this.conn.setAutoCommit(false);
      this.shutDown = false;
    } catch (Exception e) {
      if (this.dbUrl != null) {
        maskedPasswordDbUrl = maskPassword(this.dbUrl);
      }
      // throttle retries for connection failures
      try {
        Thread.sleep(200);
      } catch (InterruptedException ie) {
        Thread.currentThread().interrupt();
      }
      throw helper.newRuntimeException(String.format(Gfxd_DB_SYNCHRONIZER__6,
          this.driverClass, maskedPasswordDbUrl), e);
    }
  }

  /** mask the known password patterns from URL for exception/log messages */
  protected static final String maskPassword(final String dbUrl) {
    String maskedPasswordDbUrl = Pattern
        .compile("(password|passwd|pwd|secret)=[^;]*", Pattern.CASE_INSENSITIVE)
        .matcher(dbUrl).replaceAll("$1=***");
    return maskedPasswordDbUrl;
  }

  public boolean processEvents(List<Event> events) {
    if (this.shutDown) {
      return false;
    }
    QueryObserver observer = QueryObserverHolder.getInstance();
    final boolean traceDBSynchronizer = this.traceDBSynchronizer = helper
        .traceDBSynchronizer() && logger.isLoggable(Level.INFO);
    final boolean traceDBSynchronizerHA = this.traceDBSynchronizerHA = helper
        .traceDBSynchronizerHA() && logger.isLoggable(Level.INFO);

    boolean completedSucessfully = false;
    String listOfEventsString = null;
    // The retval will be considered true only if the list was iterated
    // completely. If the List iteration was incomplete we will return
    // false so that the events are not removed during failure.
    // As for individual events, they can get exceptions due to constraint
    // violations etc but will not cause return value to be false.
    Statement stmt = null;
    PreparedStatement ps = null;
    // keep track of the previous prepared statement in case we can optimize
    // by create a batch when the previous and current statements match
    PreparedStatement prevPS = null;
    Event prevEvent = null;
    boolean prevPSHasBatch = false;
    Iterator<Event> itr = events.iterator();
    boolean isPKBased = false;
    boolean prevIsPKBased = false;
    Event event = null;
    Event.Type evType = null;
    String eventString = null;
    String prevEventStr = null;
    try {
      while (!(completedSucessfully = !itr.hasNext())) {
        event = itr.next();
        evType = event.getType();
        if (traceDBSynchronizer || traceDBSynchronizerHA) {
          eventString = event.toString();
          if (prevEvent != null) {
            prevEventStr = prevEvent.toString();
          }
        }
        else {
          eventString = null;
          prevEventStr = null;
        }
        // first check if the event is primary key based insert/update/delete
        // operation
        if (!evType.isBulkOperation()) {
          isPKBased = true;
          // is PK based
          if (traceDBSynchronizer) {
            logger.info("DBSynchronizer::processEvents :processing PK based "
                + "event=" + eventString + " Event Type=" + evType);
          }
          try {
            switch (evType) {
              case AFTER_INSERT:
                ps = getExecutableInsertPrepStmntPKBased(event, prevPS);
                break;
              case AFTER_UPDATE:
                ps = getExecutableUpdatePrepStmntPKBased(event, prevPS);
                break;
              case AFTER_DELETE:
                ps = getExecutableDeletePrepStmntPKBased(event, prevPS);
                break;
              default:
                logger.severe("DBSynchronizer::processEvents: unexpected "
                    + "eventType " + evType + " for " + event);
                continue;
            }
          } catch (SQLException sqle) {
            SqlExceptionHandler handler = handleSQLException(sqle,
                Gfxd_DB_SYNCHRONIZER__1, null, event, eventString, logger, true);
            if (handler.breakTheLoop()) {
              break;
            }
          } catch (RegionDestroyedException rde) {
            if (logger.isLoggable(Level.INFO)) {
              logger.info("DBSynchronizer::processEvents: WBCLEvent " + event
                  + " will  be discarded as the underlying region "
                  + "for the table has been destroyed");
            }
            continue;
          }
        }
        else {
          isPKBased = false;
          try {
            // prepare the statement for a bulk DML or bulk insert operation;
            // avoid creating a prepared statement if there are no parameters
            // since it is likely to be an unprepared statement that was
            // fired in GemFireXD and we don't want to bloat the backend databases
            // prepared statement cache
            ps = getExecutablePrepStmntBulkOp(event, prevPS);
            if (ps == null && stmt == null) {
              stmt = this.conn.createStatement();
            }
          } catch (SQLException sqle) {
            SqlExceptionHandler handler = handleSQLException(sqle,
                Gfxd_DB_SYNCHRONIZER__1, null, event, eventString, this.logger,
                true);
            if (handler.breakTheLoop()) {
              break;
            }
          }
          if (traceDBSynchronizer) {
            logger.info("DBSynchronizer::processEvents: processing "
                + "Bulk DML Event=" + event);
          }
        }
        if (traceDBSynchronizer) {
          if (eventString == null) {
            eventString = event.toString();
          }
          logger.info("DBSynchronizer::processEvents: Statement="
              + (ps != null ? ps : stmt) + " for event=" + eventString);
        }
        try {
          int num;
          // check for explicit bulk insert statements first
          if (evType.isBulkInsert()) {
            // need to execute the previous batch/update first since the bulk
            // insert statement will be executed as a single batch in itself
            if (prevPS != null) {
              try {
                if (prevPSHasBatch) {
                  prevPS.addBatch();
                  if (traceDBSynchronizer || traceDBSynchronizerHA) {
                    logger.info("DBSynchronizer::processEvents executing "
                        + "batch statement for prepared statement=" + prevPS
                        + " for event=" + prevEventStr);
                  }
                  final int[] res = prevPS.executeBatch();
                  num = res.length;
                  prevPSHasBatch = false;
                }
                else {
                  num = prevPS.executeUpdate();
                }
                if (traceDBSynchronizer || traceDBSynchronizerHA) {
                  logger.info("DBSynchronizer::processEvents num rows "
                      + "modified=" + num + " for prepared statement=" + prevPS
                      + " for event=" + prevEventStr);
                }
                // clear event from failure map if present
                helper.removeEventFromFailureMap(prevEvent);
                if (observer != null) {
                  if (prevIsPKBased) {
                    observer.afterPKBasedDBSynchExecution(prevEvent.getType(),
                        num, prevPS);
                  }
                  else {
                    observer.afterBulkOpDBSynchExecution(prevEvent.getType(),
                        num, prevPS, prevEvent.getDMLString());
                  }
                }
              } catch (SQLException sqle) {
                if (prevPSHasBatch) {
                  try {
                    prevPS.clearBatch();
                  } catch (SQLException e) {
                    // ignored
                  }
                  prevPSHasBatch = false;
                }
                SqlExceptionHandler handler = handleSQLException(sqle,
                    Gfxd_DB_SYNCHRONIZER__3, prevPS, prevEvent, prevEventStr,
                    logger, false);
                if (handler.breakTheLoop()) {
                  break;
                }
              }
            }
            prevPS = null;
            prevEvent = null;
            prevPSHasBatch = false;
            prevIsPKBased = false;
            if (traceDBSynchronizer) {
              logger.info("DBSynchronizer::processEvents executing batch "
                  + "statement for bulk statement=" + ps + " for event="
                  + eventString);
            }
            final int[] res = ps.executeBatch();
            num = res.length;
            // clear event from failure map if present
            helper.removeEventFromFailureMap(event);
            if (traceDBSynchronizer || traceDBSynchronizerHA) {
              logger.info("DBSynchronizer::processEvents total num rows "
                  + "modified=" + num + " for statement="
                  + (evType.isBulkInsert() ? ps : stmt) + " for event="
                  + eventString);
            }
            if (observer != null) {
              observer.afterBulkOpDBSynchExecution(evType, num,
                  ps, event.getDMLString());
            }
          }
          else {
            // in case the previous prepared statement does not match the
            // current one, then need to execute the previous batch/update
            if (prevPS != null && prevPS != ps) {
              try {
                if (prevPSHasBatch) {
                  prevPS.addBatch();
                  if (traceDBSynchronizer) {
                    logger.info("DBSynchronizer::processEvents executing "
                        + "batch statement for prepared statement=" + prevPS
                        + " for event=" + prevEventStr);
                  }
                  final int[] res = prevPS.executeBatch();
                  num = res.length;
                  prevPSHasBatch = false;
                }
                else {
                  num = prevPS.executeUpdate();
                }
                if (traceDBSynchronizer || traceDBSynchronizerHA) {
                  logger.info("DBSynchronizer::processEvents total num rows "
                      + "modified=" + num + " for prepared statement=" + prevPS
                      + " for event=" + prevEventStr);
                }
                // clear event from failure map if present
                helper.removeEventFromFailureMap(prevEvent);
                if (observer != null) {
                  if (prevIsPKBased) {
                    observer.afterPKBasedDBSynchExecution(prevEvent.getType(),
                        num, prevPS);
                  }
                  else {
                    observer.afterBulkOpDBSynchExecution(prevEvent.getType(),
                        num, prevPS, prevEvent.getDMLString());
                  }
                }
              } catch (SQLException sqle) {
                if (prevPSHasBatch) {
                  try {
                    prevPS.clearBatch();
                  } catch (SQLException e) {
                    // ignored
                  }
                  prevPSHasBatch = false;
                }
                SqlExceptionHandler handler = handleSQLException(sqle,
                    Gfxd_DB_SYNCHRONIZER__3, prevPS, prevEvent, prevEventStr,
                    logger, false);
                if (handler.breakTheLoop()) {
                  break;
                }
                prevPS = null;
                prevEvent = null;
                prevPSHasBatch = false;
                prevIsPKBased = false;
              }
            }
            // in case previous prepared statement matches the current one,
            // it will already be added as a batch when setting the arguments
            // by AsyncEventHelper#setColumnInPrepStatement()
            else if (prevPS != null && ps != null) {
              prevPSHasBatch = true;
              if (traceDBSynchronizer) {
                logger.info("DBSynchronizer::processEvents added new row "
                    + "as a batch for prepared statement=" + ps + " for event="
                    + eventString);
              }
            }
            // execute a non-prepared statement
            if (ps == null) {
              if (traceDBSynchronizer) {
                logger.info("DBSynchronizer::processEvents executing "
                    + "unprepared statement for event=" + eventString);
              }
              final int n = stmt.executeUpdate(event.getDMLString());
              if (traceDBSynchronizer || traceDBSynchronizerHA) {
                logger.info("DBSynchronizer::processEvents num rows "
                    + "modified=" + n + " for statement=" + stmt
                    + " for event=" + eventString);
              }
              num = n;
              // clear event from failure map if present
              helper.removeEventFromFailureMap(event);
              if (observer != null) {
                observer.afterBulkOpDBSynchExecution(evType, num, stmt,
                    event.getDMLString());
              }
            }
            prevPS = ps;
            prevEvent = event;
            prevIsPKBased = isPKBased;
          }
        } catch (SQLException sqle) {
          if (prevPS != null && prevPSHasBatch) {
            try {
              prevPS.clearBatch();
            } catch (SQLException e) {
              // ignored
            }
          }
          SqlExceptionHandler handler = handleSQLException(sqle,
              Gfxd_DB_SYNCHRONIZER__3, ps != null ? ps : stmt, event,
              eventString, logger, false);
          if (handler.breakTheLoop()) {
            break;
          }
        }
      } // end of while (event list processing loop)

      // now handle the last statement in the above loop since it is still
      // pending due to anticipated batching
      if (completedSucessfully) {
        try {
          if (helper.logFineEnabled()) {
            if (listOfEventsString == null) {
              listOfEventsString = events.toString();
            }
            logger.fine("DBSynchronizer::processEvents: "
                + "before commit of events=" + listOfEventsString);
          }
          int num;
          // first the case when the previous statement was a batched one
          // so add current one as batch and execute
          if (prevPSHasBatch) {
            ps.addBatch();
            if (traceDBSynchronizer) {
              logger.info("DBSynchronizer::processEvents executing batch "
                  + "statement for prepared statement=" + ps + " for event="
                  + eventString);
            }
            final int[] res = ps.executeBatch();
            num = res.length;
            if (event != null) {
              // clear event from failure map if present
              helper.removeEventFromFailureMap(event);
              if (observer != null) {
                if (isPKBased) {
                  observer.afterPKBasedDBSynchExecution(evType, num, ps);
                }
                else {
                  observer.afterBulkOpDBSynchExecution(evType, num, ps,
                      event.getDMLString());
                }
              }
            }
          }
          // next the case of a non BULK_INSERT operation;
          // BULK_INSERT operations are always executed as a single batch
          // by itself, so will never reach here
          else if (ps != null && !evType.isBulkInsert()) {
            num = ps.executeUpdate();
            if (event != null) {
              // clear event from failure map if present
              helper.removeEventFromFailureMap(event);
              if (observer != null) {
                if (isPKBased) {
                  observer.afterPKBasedDBSynchExecution(evType, num, ps);
                }
                else {
                  observer.afterBulkOpDBSynchExecution(evType, num, ps,
                      event.getDMLString());
                }
              }
            }
          }
          else {
            num = 0;
          }
          // clear event from failure map if present
          helper.removeEventFromFailureMap(event);
          if (traceDBSynchronizer || traceDBSynchronizerHA) {
            if (ps != null) {
              logger.info("DBSynchronizer::processEvents num rows modified="
                  + num + " for prepared statement=" + ps + " for event="
                  + eventString);
            }
          }
          this.conn.commit();
          if (observer != null) {
            observer.afterCommitDBSynchExecution(events);
          }
          if (helper.logFineEnabled()) {
            if (listOfEventsString == null) {
              listOfEventsString = events.toString();
            }
            logger.fine("DBSynchronizer::processEvents: "
                + "committed successfully for events=" + listOfEventsString);
          }
        } catch (SQLException sqle) {

          if (ps != null && prevPSHasBatch) {
            try {
              ps.clearBatch();
            } catch (SQLException e) {
              // ignored
            }
          }

          SqlExceptionHandler handler = handleSQLException(sqle,
              Gfxd_DB_SYNCHRONIZER__7, ps != null ? ps : stmt, event,
              eventString, logger, true);
          if (handler != SqlExceptionHandler.IGNORE) {
            completedSucessfully = false;
          }
        }
      }
    } catch (Exception e) {

      if (logger != null && logger.isLoggable(Level.SEVERE)
          && !(event != null && helper.skipFailureLogging(event))) {
        StringBuilder sb = new StringBuilder();
        if (event != null) {
          if (eventString == null) {
            eventString = event.toString();
          }
          sb.append("[FAILED: ").append(eventString).append(" ]");
        }
        while (itr.hasNext()) {
          sb.append("[ ").append(itr.next().toString()).append(" ]");
        }
        helper.logFormat(logger, Level.SEVERE, e, Gfxd_DB_SYNCHRONIZER__2,
            sb.toString());
      }
      SqlExceptionHandler.CLEANUP.execute(this);
      completedSucessfully = false;
    }

    if (completedSucessfully) {
      // on successful completion, log any pending errors to XML file; when
      // unsuccessful then we know that batch will be retried so don't log in
      // that case else it can get logged multiple times
      // clear event from failure map if present
      flushErrorEventsToLog();
    }    
    
    if (helper.traceExecute()) {
      logger.info("DBSynchronizer::processEvents: processed " + events.size()
          + " events, success=" + completedSucessfully);
    }

    return completedSucessfully;
  }
  
  private void flushErrorEventsToLog() {
    Iterator<ErrorEvent> it = errorTriesMap.keySet().iterator();
    while (it.hasNext()) {
      ErrorEvent ee = it.next();
      Object[] tries = errorTriesMap.get(ee);
      if (tries != null && tries[1] != null) {
        try {
          helper.logEventError(ee.ev, (SQLException)tries[1]);
        } catch (Exception e) {
          // failed to even log the exception
          if (logger.isLoggable(Level.WARNING)) {
            helper.log(logger, Level.WARNING, e, e.getMessage());
          }
        }
      }      
    }
    errorTriesMap.clear();
  }

  /**
   * Get or create a {@link PreparedStatement} for a
   * {@link Event.Type#isBulkOperation()} type of operation.
   */
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value = "SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING")
  protected PreparedStatement getExecutablePrepStmntBulkOp(Event event,
      PreparedStatement prevPS) throws SQLException {
    String dmlString = event.getDMLString();
    if (event.hasParameters() || (!this.skipIdentityColumns && event.tableHasAutogeneratedColumns())) {
      // for IDENTITY column bulk insert case rebuild the DML string if required
      if (!this.skipIdentityColumns && event.tableHasAutogeneratedColumns()
          && event.getType().isBulkInsert()) {
        dmlString = AsyncEventHelper.getInsertString(event.getTableName(),
            event.getResultSetMetaData(), false);
      }

      PreparedStatement ps = this.bulkOpStmntMap.get(dmlString);
      if (ps == null) {
        if (traceDBSynchronizer) {
          logger.info("DBSynchronizer::getExecutablePrepStmntBulkOp: "
              + "preparing '" + dmlString + "' for event: " + event);
        }
        ps = conn.prepareStatement(dmlString);
        this.bulkOpStmntMap.put(dmlString, ps);
      }
      helper.setParamsInBulkPreparedStatement(event, event.getType(), ps,
          prevPS, this);
      return ps;
    }
    else {
      // indicator to use unprepared statement
      return null;
    }
  }

  /**
   * Get or create a {@link PreparedStatement} for an insert operation.
   */
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING")
  protected PreparedStatement getExecutableInsertPrepStmntPKBased(Event pkEvent,
      PreparedStatement prevPS) throws SQLException {
    final String tableName = pkEvent.getTableName();
    PreparedStatement ps = this.insertStmntMap.get(tableName);
    final TableMetaData tableMetaData = pkEvent.getResultSetMetaData();
    final boolean skipAutoGenCols = this.skipIdentityColumns 
        && pkEvent.tableHasAutogeneratedColumns(); 
    
    if (ps == null || (tableMetaData != this.metadataMap.get(tableName))) {
      final String dmlString = AsyncEventHelper.getInsertString(tableName,
          tableMetaData, skipAutoGenCols);
      if (traceDBSynchronizer) {
        logger.info("DBSynchronizer::getExecutableInsertPrepStmntPKBased: "
            + "preparing '" + dmlString + "' for event: " + pkEvent);
      }
      ps = conn.prepareStatement(dmlString);
      this.insertStmntMap.put(tableName, ps);
      this.metadataMap.put(tableName, tableMetaData);
    }
    else if (prevPS == ps) {
      // add a new batch of values
      ps.addBatch();
    }
    final ResultSet row = pkEvent.getNewRowsAsResultSet();
    int paramIndex = 1; 
    for (int colIdx = 1; colIdx <= tableMetaData.getColumnCount(); colIdx++) {
      if (!skipAutoGenCols || !tableMetaData.isAutoIncrement(colIdx)) { 
        helper.setColumnInPrepStatement(tableMetaData.getColumnType(colIdx), 
            ps, row, colIdx, paramIndex, this); 
        paramIndex++; 
      }
    }
    return ps;
  }

  /**
   * Get or create a {@link PreparedStatement} for a primary key based delete
   * operation.
   */
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING")
  protected PreparedStatement getExecutableDeletePrepStmntPKBased(Event pkEvent,
      PreparedStatement prevPS) throws SQLException {

    final String tableName = pkEvent.getTableName();
    PreparedStatement ps = this.deleteStmntMap.get(tableName);
    final ResultSet pkResultSet = pkEvent.getPrimaryKeysAsResultSet();
    final ResultSetMetaData pkMetaData = pkResultSet.getMetaData();
    if (ps == null || pkMetaData != this.pkMetadataMap.get(tableName)) {
      final String dmlString = AsyncEventHelper
          .getDeleteString(tableName, pkMetaData);
      if (traceDBSynchronizer) {
        logger.info("DBSynchronizer::getExecutableInsertPrepStmntPKBased: "
            + "preparing '" + dmlString + "' for event: " + pkEvent);
      }
      ps = conn.prepareStatement(dmlString);
      this.deleteStmntMap.put(tableName, ps);
      this.pkMetadataMap.put(tableName, pkMetaData);
    }
    else if (prevPS == ps) {
      // add a new batch of values
      ps.addBatch();
    }
    setKeysInPrepStatement(pkResultSet, pkMetaData, ps, 1);
    return ps;
  }

  /**
   * Get or create a {@link PreparedStatement} for a primary key based update
   * operation.
   */
  @edu.umd.cs.findbugs.annotations.SuppressWarnings(value="SQL_PREPARED_STATEMENT_GENERATED_FROM_NONCONSTANT_STRING")
  protected PreparedStatement getExecutableUpdatePrepStmntPKBased(Event pkEvent,
      PreparedStatement prevPS) throws SQLException {
    final String tableName = pkEvent.getTableName();
    final ResultSet updatedRow = pkEvent.getNewRowsAsResultSet();
    final TableMetaData updateMetaData = (TableMetaData)updatedRow
        .getMetaData();
    final int numUpdatedCols = updateMetaData.getColumnCount();
    StringBuilder searchKeyBuff = new StringBuilder(tableName);
    int paramIndex;
    for (paramIndex = 1; paramIndex <= numUpdatedCols; paramIndex++) {
      searchKeyBuff.append('_');
      searchKeyBuff.append(updateMetaData.getTableColumnPosition(paramIndex));
    }
    String searchKey = searchKeyBuff.toString();
    final ResultSet pkValues = pkEvent.getPrimaryKeysAsResultSet();
    final ResultSetMetaData pkMetaData = pkValues.getMetaData();
    PreparedStatement ps = this.updtStmntMap.get(searchKey);
    if (ps == null || pkMetaData != this.pkMetadataMap.get(tableName)) {
      final String dmlString = AsyncEventHelper.getUpdateString(tableName,
          pkMetaData, updateMetaData);
      if (traceDBSynchronizer) {
        logger.info("DBSynchronizer::getExecutableInsertPrepStmntPKBased: "
            + "preparing '" + dmlString + "' for event: " + pkEvent);
      }
      ps = conn.prepareStatement(dmlString);
      this.updtStmntMap.put(searchKey, ps);
      this.pkMetadataMap.put(tableName, pkMetaData);
    }
    else if (prevPS == ps) {
      // add a new batch of values
      ps.addBatch();
    }
    // Set updated col values
    for (paramIndex = 1; paramIndex <= numUpdatedCols; paramIndex++) {
      helper.setColumnInPrepStatement(updateMetaData.getColumnType(paramIndex),
          ps, updatedRow, paramIndex, paramIndex, this);
    }
    // Now set the Pk values
    setKeysInPrepStatement(pkValues, pkMetaData, ps, paramIndex);
    return ps;
  }

  /**
   * Set the key column values in {@link PreparedStatement} for a primary key
   * based update or delete operation.
   */
  protected void setKeysInPrepStatement(final ResultSet pkValues,
      final ResultSetMetaData pkMetaData, final PreparedStatement ps,
      int startIndex) throws SQLException {
    final int numKeyCols = pkMetaData.getColumnCount();
    if (traceDBSynchronizer) {
      StringBuilder sb = new StringBuilder()
          .append("DBSynchronizer::setKeysInPrepStatement: setting key {");
      for (int col = 1; col <= numKeyCols; col++) {
        if (col > 1) {
          sb.append(',');
        }
        sb.append(pkValues.getObject(col));
      }
      sb.append('}');
      logger.info(sb.toString());
    }
    for (int colIndex = 1; colIndex <= numKeyCols; colIndex++, startIndex++) {
      helper.setColumnInPrepStatement(pkMetaData.getColumnType(colIndex), ps,
          pkValues, colIndex, startIndex, this);
    }
  }

  /**
   * Returns an {@link SqlExceptionHandler} for the given {@link SQLException}.
   */
  protected SqlExceptionHandler handleSQLException(SQLException sqle) {
    String sqlState = sqle.getSQLState();
    // What to do if SQLState is null? Checking through the exception
    // message for common strings for now but DB specific errorCode and other
    // such checks will be better.
    // Below was due to a bug in wrapper OracleDriver being used and normally
    // this can never be null.
    if (sqlState == null) {
      // no SQLState so fallback to string matching in the message
      // for BatchUpdateException it will look at the nextException
      if (sqle instanceof BatchUpdateException
          && sqle.getNextException() != null) {
        // "42Y96" represents an unknown exception but batch exception will
        // look at the nextException in any case
        sqlState = "42Y96";
      }
      else {
        // if connection has been closed then refresh it
        try {
          synchronized (this) {
            if (this.conn == null || this.conn.isClosed()) {
              return SqlExceptionHandler.REFRESH;
            }
          }
        } catch (Exception e) {
          return SqlExceptionHandler.REFRESH;
        }
        // treat like a connection failure by default
        return checkExceptionString(sqle.toString().toLowerCase(),
            SqlExceptionHandler.REFRESH);
      }
    }
    // check for exception type first
    SqlExceptionHandler handler = checkExceptionType(sqle);
    if (handler != null) {
      return handler;
    }
    // next check SQLStates
    if (sqlState.startsWith(AsyncEventHelper.INTEGRITY_VIOLATION_PREFIX)
        || sqlState.startsWith("25")) {
      // constraint violations can happen in retries, so default action is to
      // IGNORE them; when errorFile is provided then it will be logged to
      // that in XML format in any case
      return SqlExceptionHandler.IGNORE;
    }
    else if (sqlState.startsWith(AsyncEventHelper.LSE_COMPILATION_PREFIX)
        || sqlState.startsWith("22") /* for SQLDataExceptions */) {
      // if numErrorTries is defined, then retry some number of times else
      // ignore after having logged warning since retry is not likely to help
      return this.numErrorTries > 0 ? SqlExceptionHandler.IGNORE_BREAK_LOOP
          : SqlExceptionHandler.IGNORE;
    }
    else if (sqlState.startsWith(AsyncEventHelper.CONNECTIVITY_PREFIX)) {
      return SqlExceptionHandler.REFRESH;
    }
    else if (sqlState.startsWith("40")) {
      // these are transient transaction/lock exceptions so retry whole batch
      return SqlExceptionHandler.IGNORE_BREAK_LOOP;
    }
    else {
      if (sqle instanceof BatchUpdateException
          && sqle.getNextException() != null) {
        return handleSQLException(sqle.getNextException());
      }
      // if connection has been closed then refresh it
      try {
        synchronized (this) {
          if (this.conn == null || this.conn.isClosed()) {
            return SqlExceptionHandler.REFRESH;
          }
        }
      } catch (Exception e) {
        return SqlExceptionHandler.REFRESH;
      }
      return checkExceptionString(sqle.toString().toLowerCase(),
          SqlExceptionHandler.REFRESH);
    }
  }
  
  protected SqlExceptionHandler checkExceptionType(SQLException sqle) {
    if (sqle != null) {
      if (sqle instanceof SQLNonTransientConnectionException) {
        // will need to connect again
        return SqlExceptionHandler.REFRESH;
      }
      if (sqle instanceof SQLIntegrityConstraintViolationException) {
        // constraint violations can happen in retries, so default action is to
        // IGNORE them; when errorFile is provided then it will be logged to
        // that in XML format in any case
        return SqlExceptionHandler.IGNORE;
      }
      if (sqle instanceof SQLNonTransientException) {
        // if numErrorTries is defined, then retry some number of times else
        // ignore after having logged warning since retry is not likely to help
        return this.numErrorTries > 0 ? SqlExceptionHandler.IGNORE_BREAK_LOOP
            : SqlExceptionHandler.IGNORE;
      }
      if (sqle instanceof SQLTransientException) {
        // skip the remaining batch and retry whole batch again
        return SqlExceptionHandler.IGNORE_BREAK_LOOP;
      }
      if (sqle instanceof BatchUpdateException) {
        return checkExceptionType(sqle.getNextException());
      }
    }
    return null;
  }

  protected SqlExceptionHandler checkExceptionString(String message,
      SqlExceptionHandler defaultHandler) {
    if (message.contains("constraint")) {
      // likely a constraint violation
      // constraint violations can happen in retries, so default action is to
      // IGNORE them; when errorFile is provided then it will be logged to
      // that in XML format in any case
      return SqlExceptionHandler.IGNORE;
    }
    else if (message.contains("syntax")) {
      // if numErrorTries is defined, then retry some number of times else
      // ignore after having logged warning since retry is not likely to help
      return this.numErrorTries > 0 ? SqlExceptionHandler.IGNORE_BREAK_LOOP
          : SqlExceptionHandler.IGNORE;
    }
    else if (message.contains("connect")) {
      // likely a connection error
      return SqlExceptionHandler.REFRESH;
    }
    else {
      return defaultHandler;
    }
  }


  /**
   * Log exception including stack traces for fine logging with
   * {@link #traceDBSynchronizer}, and returns an {@link SqlExceptionHandler}
   * for the given {@link SQLException}.
   */
  protected SqlExceptionHandler handleSQLException(SQLException sqle,
      String format, Statement stmt, Event event, String eventString,
      Logger logger, boolean logWarning) throws SQLException {
    SqlExceptionHandler handler = handleSQLException(sqle);
    
    if (event != null && this.numErrorTries > 0) {
      
      ErrorEvent ee = new ErrorEvent();
      ee.ev = event;
      ee.errortime = System.currentTimeMillis();
      Object[] tries = this.errorTriesMap.get(ee);

      if (tries != null) {
        Integer numTries = (Integer)tries[0];
        if (numTries >= this.numErrorTries) {
          // at this point ignore this exception and move to others in the batch
          handler = SqlExceptionHandler.IGNORE;
          logWarning = false;
        }
        tries[0] = Integer.valueOf(numTries.intValue() + 1);
        tries[1] = sqle;
      }
      else {
        this.errorTriesMap.put(ee, new Object[] { 1, sqle });
      }
    }
    
    boolean skipLogging = false;
    if (event != null && (logWarning || traceDBSynchronizer)) {
      skipLogging = helper.skipFailureLogging(event);
      if (eventString == null) {
        eventString = event.toString();
      }
    }
    if (!skipLogging) {
      if (logWarning) {
        if (logger.isLoggable(Level.WARNING)) {
          helper.logFormat(logger, Level.WARNING, sqle, format, eventString,
              sqle);
          SQLException next = sqle.getNextException();
          if (next != null) {
            helper.logFormat(logger, Level.WARNING, next, format, eventString,
                sqle.getNextException());
          }
        }
      }
      if (traceDBSynchronizer) {
        if (logger.isLoggable(Level.WARNING)) {
          String stmtStr = (stmt != null ? ("executing statement=" + stmt)
              : "preparing statement");
          helper.log(logger, Level.WARNING, sqle, "DBSynchronizer::"
              + "processEvents: Exception while " + stmtStr + " for event="
              + eventString);
          if (sqle.getNextException() != null) {
            helper.log(logger, Level.WARNING, sqle.getNextException(),
                "DBSynchronizer::processEvents: next exception");
          }
        }
      }
    }

    handler.execute(this);
    return handler;
  }

  @Override
  public synchronized void start() {
    if (this.shutDown) {
      this.instantiateConnection();
    }
  }
  
  /**
   * Returns true if this DBSynchronizer has been configured to skip IDENTITY
   * column values when syncing to backend database (the default behaviour) and
   * false if IDENTITY column values from GemFireXD should also be synced
   */
  protected final boolean skipIdentityColumns() {
    return this.skipIdentityColumns;
  }
}
