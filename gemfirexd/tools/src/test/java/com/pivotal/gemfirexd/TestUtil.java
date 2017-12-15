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
package com.pivotal.gemfirexd;

import java.io.*;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.net.UnknownHostException;
import java.security.AccessController;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import com.gemstone.gemfire.GemFireTestCase;
import com.gemstone.gemfire.cache.PartitionAttributes;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionManager;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.NanoTimer;
import com.gemstone.gemfire.internal.cache.CacheServerLauncher;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionAttributesImpl;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.UserSpecifiedRegionAttributes;
import com.gemstone.gemfire.internal.cache.locks.ExclusiveSharedSynchronizer;
import com.gemstone.gemfire.internal.cache.xmlcache.CacheCreation;
import com.gemstone.gemfire.internal.cache.xmlcache.CacheXmlGenerator;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionAttributesCreation;
import com.gemstone.gemfire.internal.cache.xmlcache.RegionCreation;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.gemfire.internal.shared.jna.OSType;
import com.gemstone.gemfire.internal.shared.StringPrintWriter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransactionContext;
import com.pivotal.gemfirexd.internal.engine.access.MemConglomerate;
import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeap;
import com.pivotal.gemfirexd.internal.engine.access.heap.MemHeapScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.GlobalHashIndexScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.Hash1IndexScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.MemIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.MemIndexScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2IndexScanController;
import com.pivotal.gemfirexd.internal.engine.db.FabricDatabase;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.messages.GfxdSystemProcedureMessage;
import com.pivotal.gemfirexd.internal.engine.ddl.resolver.GfxdPartitionResolver;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.sql.catalog.DistributionDescriptor;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.cache.CacheManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.BackingStoreHashtable;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueFactory;
import com.pivotal.gemfirexd.internal.iapi.util.StringUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.impl.store.access.sort.Scan;
import io.snappydata.test.dunit.DistributedTestBase.InitializeRun;
import junit.framework.TestCase;
import org.apache.derbyTesting.junit.TestConfiguration;
import org.apache.log4j.LogManager;
import org.apache.log4j.Logger;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.ErrorHandler;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;

/**
 * Utility class for running SQL tests. It provides the following capabilities:
 * 
 * 1) Create a JDBC connection which is maitained statically and also close it.
 * 
 * 2) Provide SQL to be executed in this VM using the connection created in 1).
 * 
 * 3) Provide SQL execution with verification against golden text for
 * verification, or just check for successful execution with at least one row
 * when golden text is not provided. The steps 2) and 3) can be freely
 * inter-mixed as required.
 * 
 * 4) For DDL tests, optionally provide a RegionAttributes object to be used for
 * verification of the region attributes in this VM.
 * 
 * This is used extensively by {@link DistributedSQLTestBase} and should also be
 * preferably used by junit tests wherever appropriate to simplify things.
 * Creating an instance of this class is not useful and disallowed since all
 * methods are static in this class.
 * 
 * @author swale
 * @since 6.0
 */
public class TestUtil extends TestCase {

  // the default framework is embedded
  private static final String driver = "io.snappydata.jdbc.EmbeddedDriver";

  private static final String netDriver = "io.snappydata.jdbc.ClientDriver";

  private static final String protocol = "jdbc:gemfirexd:";

  private static final String odbcBridge = "sun.jdbc.odbc.JdbcOdbcDriver";

  /**
   * system property to switch to testing GemFireXD ODBC driver using JDBC-ODBC
   * bridge
   */
  public static final String USE_ODBC_BRIDGE_PROP =
    TestConfiguration.USE_ODBC_BRIDGE_PROP;

  public static final boolean USE_ODBC_BRIDGE =
    TestConfiguration.USE_ODBC_BRIDGE;

  public static Connection jdbcConn = null;

  public static final Logger globalLogger = LogManager.getLogger(TestUtil.class);
  public final Logger logger = LogManager.getLogger(getClass());

  private static Map<String, Integer> expectedExceptions =
    new HashMap<String, Integer>();

  /** Internal test property to add expected exceptions during startup. */
  public static final String EXPECTED_STARTUP_EXCEPTIONS =
      "test.expected-startup-exceptions";

  public static String currentTest = null;

  public static Class<?> currentTestClass = null;

  public static String currentUserName = null;

  public static String bootUserName = null;

  public static String bootUserPassword = null;

  public static String currentUserPassword = null;

  private static final Set<String> initSysProperties;

  public static boolean isTransactional = false;

  public volatile static boolean deletePersistentFiles = false;

  public volatile static boolean skipDefaultPartitioned = false;

  public static final int TEST_DEFAULT_INITIAL_CAPACITY = 5;

  public static final String TEST_SKIP_DEFAULT_INITIAL_CAPACITY =
      "gemfirexd.test.skip-default-initial-capacity";

  public static final String TEST_FLAG_ENABLE_CONCURRENCY_CHECKS = "gemfirexd.TEST_FLAG_ENABLE_CONCURRENCY_CHECKS";

  /** constructor that invokes the base class constructor */
  protected TestUtil(String name) {
    super(name);
  }

  protected String reduceLogging() {
    return null;
  }

  protected void reduceLogLevelForTest(String logLevel) {
    reduceLogLevel(logLevel);
  }

  private static boolean oldDMVerbose = false;

  static {
    InitializeRun.setUp();
    initSysProperties = System.getProperties().stringPropertyNames();
  }

  public static final void reduceLogLevel(String logLevel) {
    if (logLevel != null) {
      oldDMVerbose = DistributionManager.VERBOSE;
      if (!logLevel.startsWith("fine")) {
        DistributionManager.VERBOSE = false;
        System.setProperty("DistributionManager.VERBOSE", "false");
      }
      System.setProperty("gemfire.log-level", logLevel);
    } else {
      DistributionManager.VERBOSE = oldDMVerbose;
      System.setProperty("DistributionManager.VERBOSE",
          Boolean.toString(oldDMVerbose));
      System.clearProperty("gemfire.log-level");
      logLevel = "config";
    }
    try {
      if (Misc.getGemFireCacheNoThrow() != null) {
        // convert logLevel to slf4j name
        String level = ClientSharedUtils.convertToLog4LogLevel(
            java.util.logging.Level.parse(logLevel.toUpperCase(Locale.ENGLISH)));
        GfxdSystemProcedureMessage.SysProcMethod.setLogLevel.processMessage(
            new Object[]{"", level}, Misc.getMyId());
      }
    } catch (Exception e) {
      getLogger().warn("Failed to set log-level " + logLevel, e);
    }
  }

  @Override
  protected void setUp() throws Exception {
    currentTest = getName();
    currentTestClass = getTestClass();

    /*
    boolean nonTXTestMode = Boolean.getBoolean(SanityManager.TEST_MODE_NON_TX)
      || Boolean.parseBoolean(System.getenv(SanityManager.TEST_MODE_NON_TX));
    isTransactional = !nonTXTestMode;
    */

    // reduce logging if test so requests
    String logLevel;
    if ((logLevel = reduceLogging()) != null) {
      reduceLogLevelForTest(logLevel);
    }
  }

  @Override
  protected void tearDown() throws Exception {
    clearStatics();
    // delete persistent DataDictionary files
    deleteDir(new File("datadictionary"));
    deleteDir(new File("globalIndex"));
    //noinspection ResultOfMethodCallIgnored
    TestConfiguration.odbcIni.delete();
    deletePersistentFiles = false;
    skipDefaultPartitioned = false;

    // reset log-level
    if (reduceLogging() != null
        || System.getProperty("gemfire.log-level") != null) {
      reduceLogLevelForTest(null);
    }
  }

  public static void setCurrentTestClass(Class<?> c) {
    currentTestClass = c;
  }

  public static String getDriver() {
    return driver;
  }

  public static String getProtocol() {
    return protocol;
  }

  public static String getNetDriver() {
    return USE_ODBC_BRIDGE ? odbcBridge : netDriver;
  }

  /**
   * Adjust the properties as per current protocol.
   */
  public static Properties getNetProperties(Properties props) {
    if (USE_ODBC_BRIDGE) {
      if (props != null) {
        Properties newProps = new Properties();
        Enumeration<?> propNames = props.propertyNames();
        String propName, propValue;
        while (propNames.hasMoreElements()) {
          propName = String.valueOf(propNames.nextElement());
          propValue = props.getProperty(propName);
          // ODBC properties as same as JDBC ones without the "-"
          newProps.setProperty(propName.replace("-", ""), propValue);
        }
        return newProps;
      }
      else {
        return null;
      }
    }
    else {
      return props;
    }
  }

  public static String getNetProtocol(String hostName, int port) {
    return TestConfiguration.getNetProtocol(hostName, port);
  }

  protected Class<?> getTestClass() {
    Class<?> clazz = getClass();
    while (clazz.getDeclaringClass() != null) {
      clazz = clazz.getDeclaringClass();
    }
    return clazz;
  }

  // Utility methods for managing JDBC connections

  /** get a new connection with default properties */
  public static DistributedMember setupConnection() throws SQLException {
    if (jdbcConn == null || jdbcConn.isClosed()) {
      loadDriver();
      getConnection();
    }
    InternalDistributedSystem ids = InternalDistributedSystem
        .getConnectedInstance();
    if (ids != null) {
      return ids.getDistributedMember();
    }
    return null;
  }

  /** get a new connection with given properties */
  public static DistributedMember setupConnection(Properties props,
      Class<?> testClass) throws SQLException {
    return setupConnection(props, testClass, null);
  }

  /** get a new connection with given properties */
  public static DistributedMember setupConnection(Properties props,
      Class<?> testClass, String testName) throws SQLException {
    if (testClass != null) {
      currentTestClass = testClass;
      if (currentTest == null) {
        if (testName == null) {
          currentTest = "testAll";
        } else {
          currentTest = testName;
        }
      } else if (testName != null) {
        currentTest = testName;
      }
    }
    return setupConnection(props);
  }

  /** get a new connection with given properties */
  public static DistributedMember setupConnection(Properties props)
      throws SQLException {
    final FabricServer server = FabricServiceManager.getFabricServerInstance();
    if (jdbcConn == null || jdbcConn.isClosed()
        || server.status() != FabricService.State.RUNNING) {
      jdbcConn = null;
      loadDriver();
      props = doCommonSetup(props);
      final Object[] expectedExceptions = (Object[])props
          .remove(EXPECTED_STARTUP_EXCEPTIONS);
      if (expectedExceptions != null) {
        GemFireStore.EXPECTED_STARTUP_EXCEPTIONS = expectedExceptions;
      }
      server.start(props);
      getLogger().info(
          "Fabric Server setup connection status : " + server.status());

      props.remove(CacheServerLauncher.REBALANCE);
      getConnection(props);
      getLogger().info("Acquired connection ");
    }

    InternalDistributedSystem ids = InternalDistributedSystem
        .getConnectedInstance();
    if (ids != null) {
      return ids.getDistributedMember();
    }
    return null;
  }

  public static DistributedMember setupConnection(
      final String locatorBindAdress, final int locatorPort,
      final String extraLocatorArgs, Properties props) throws SQLException {
    final FabricLocator locator = FabricServiceManager
        .getFabricLocatorInstance();
    if (jdbcConn == null || jdbcConn.isClosed()
        || locator.status() != FabricService.State.RUNNING) {
      jdbcConn = null;
      loadDriver();
      props = doCommonSetup(props);
      final Object[] expectedExceptions = (Object[])props
          .remove(EXPECTED_STARTUP_EXCEPTIONS);
      if (expectedExceptions != null) {
        GemFireStore.EXPECTED_STARTUP_EXCEPTIONS = expectedExceptions;
      }
      locator.start(locatorBindAdress, locatorPort, props);
      getLogger().info(
          "Fabric Locator setup connection status : " + locator.status());

      getConnection(props);
      getLogger().info("Acquired connection ");
    }

    InternalDistributedSystem ids = InternalDistributedSystem
        .getConnectedInstance();
    if (ids != null) {
      return ids.getDistributedMember();
    }
    return null;
  }

  public static boolean setPropertyIfAbsent(Properties props, String key,
      String value) {
    if (props == null) { // null indicates System property
      if (!key.startsWith(DistributionConfig.GEMFIRE_PREFIX)
          && !key.startsWith(GfxdConstants.GFXD_PREFIX)) {
        key = DistributionConfig.GEMFIRE_PREFIX + key;
      }
      if (System.getProperty(key) == null) {
        System.setProperty(key, value);
        return true;
      }
    }
    else if (!props.containsKey(key)) {
      props.put(key, value);
      return true;
    }
    return false;
  }

  protected static String getTestName() {
    return currentTestClass.getName() + "-" + currentTest;
  }

  public static Connection getConnection(Properties props) throws SQLException {
    return getConnection(getProtocol(), props);
  }

  public static String getResourcesDir() {
    return GemFireTestCase.getResourcesDir();
  }

  public static String getProcessOutput(final Process p,
      final int expectedExitValue, final int maxWaitMillis,
      final int[] exitValue) throws IOException, InterruptedException {
    return GemFireTestCase.getProcessOutput(p, expectedExitValue,
        maxWaitMillis, exitValue);
  }

  public static Properties doMinimalSetup(Properties props) {
    if (props == null) {
      props = new Properties();
    }
    // don't pre-compile SPS descriptors by default to reduce test run times;
    // tests that explicitly check for deadlock scenarios with SPSDescriptors
    // should set the property "SKIP_SPS_PRECOMPILE" explicitly to false
    Object skipSPSPrecompile = props.remove("SKIP_SPS_PRECOMPILE");
    // do below only if this VM is not yet booted
    if (GemFireCacheImpl.getInstance() == null) {
      if (skipSPSPrecompile != null
          && "false".equalsIgnoreCase(skipSPSPrecompile.toString())) {
        FabricDatabase.SKIP_SPS_PRECOMPILE = false;
      } else {
        FabricDatabase.SKIP_SPS_PRECOMPILE = true;
      }
    }

    if (currentTestClass != null && currentTest != null) {
      String testName = getTestName();
      if (setPropertyIfAbsent(props, DistributionConfig.LOG_FILE_NAME,
          testName + ".log")) {
        // if no log-file property then also set the system property for
        // GemFireXD log-file that will also get used for JDBC clients
        setPropertyIfAbsent(null, GfxdConstants.GFXD_LOG_FILE,
            testName + ".log");
      }
      setPropertyIfAbsent(props,
          DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
          testName + ".gfs");
      // also set the client driver properties
      setPropertyIfAbsent(null, GfxdConstants.GFXD_CLIENT_LOG_FILE,
          testName + "-client.log");
    }

    // set default bind-address to localhost so tests can be run
    // even if network interfaces change
    setPropertyIfAbsent(props, DistributionConfig.BIND_ADDRESS_NAME,
        "localhost");

    // set mcast port to zero if not set
    setPropertyIfAbsent(props, "mcast-port", "0");
    return props;
  }

  public static Properties doCommonSetup(Properties props) {

    props = doMinimalSetup(props);
    // set default partitioned policy if not set
    if (!skipDefaultPartitioned) {
      setPropertyIfAbsent(props, Attribute.TABLE_DEFAULT_PARTITIONED, "true");
    }

    // turn off some trace flags to reduce the logs
    // don't disable debug flags if some have been set specifically
    if (System.getProperty("gemfirexd.debug.true") == null) {
      setPropertyIfAbsent(null, "gemfirexd.debug.false",
          GfxdConstants.TRACE_DDLQUEUE + ','
              + GfxdConstants.TRACE_QUERYDISTRIB + ','
              + GfxdConstants.TRACE_NON_COLLOCATED_JOIN + ','
              + GfxdConstants.TRACE_NCJ_ITER + ','
              + GfxdConstants.TRACE_NCJ_DUMP + ','
              + GfxdConstants.TRACE_CONGLOM_UPDATE + ','
              + GfxdConstants.TRACE_BYTE_COMPARE_OPTIMIZATION + ','
              + GfxdConstants.TRACE_STATEMENT_MATCHING + ','
              + SanityManager.TRACE_CLIENT_HA + ','
              + SanityManager.TRACE_SINGLE_HOP);
    }

    // for unit tests reduce lock timeout values
    setPropertyIfAbsent(props, GfxdConstants.MAX_LOCKWAIT, "60000");
    // use a small default recovery delay value
    setPropertyIfAbsent(props, Attribute.DEFAULT_RECOVERY_DELAY_PROP,
        "1000");

//    setPropertyIfAbsent(props, "gemfirexd.debug.true",
//        GfxdConstants.TRACE_DB_SYNCHRONIZER);
    
    if (props.remove(TEST_SKIP_DEFAULT_INITIAL_CAPACITY) == null) {
      // use a small initial capacity to force rehashes in unit tests
      setPropertyIfAbsent(props, Attribute.DEFAULT_INITIAL_CAPACITY_PROP,
          String.valueOf(TEST_DEFAULT_INITIAL_CAPACITY));
    }

    final Random rand = PartitionedRegion.rand;
    if (currentUserName != null) {
      if (!props.containsKey(Attribute.USERNAME_ALT_ATTR)) {
        setPropertyIfAbsent(props, Attribute.USERNAME_ATTR, currentUserName);
      }
      setPropertyIfAbsent(props, Attribute.PASSWORD_ATTR, currentUserPassword);
    }
    // set wait for PR recovery during close to a small value instead of default
    // of 120; causes many failover tests to just hang for 120 secs when
    // rebalance thread keeps waiting for dlock replies from itself!
    setPropertyIfAbsent(null, "gemfire.prrecovery-close-timeout", "5");

    // reduce wait for read-write conflict detection and timeouts
    setPropertyIfAbsent(null,
        ExclusiveSharedSynchronizer.READ_LOCK_TIMEOUT_PROP, "100");
    setPropertyIfAbsent(null,
        ExclusiveSharedSynchronizer.LOCK_MAX_TIMEOUT_PROP, "20000");

    // allow running in background that gets stuck due to jline usage
    setPropertyIfAbsent(null, "jline.terminal", "jline.UnsupportedTerminal");

    // randomly set the streaming flag
    setPropertyIfAbsent(props, Attribute.DISABLE_STREAMING,
        Boolean.toString(rand.nextBoolean()));
    
    if (props.containsKey(TEST_FLAG_ENABLE_CONCURRENCY_CHECKS)) {
      System.setProperty(TEST_FLAG_ENABLE_CONCURRENCY_CHECKS,
          props.getProperty(TEST_FLAG_ENABLE_CONCURRENCY_CHECKS));
    }
    
    //Allow HDFS tests to create a standalone file system
    System.setProperty(HDFSStoreImpl.ALLOW_STANDALONE_HDFS_FILESYSTEM_PROP, "true");

    /*
    boolean nonTXTestMode = Boolean.getBoolean(SanityManager.TEST_MODE_NON_TX)
        || Boolean.parseBoolean(System.getenv(SanityManager.TEST_MODE_NON_TX)); 
    if (!nonTXTestMode) {
      props.put(Attribute.TX_SYNC_COMMITS, "true");
    }
    */
    
    return props;
  }

  public static synchronized Connection getConnection(String protocol,
      Properties props) throws SQLException {

    loadDriverClass(getDriver());
    props = doCommonSetup(props);

    final Connection conn = DriverManager.getConnection(protocol, props);
    /*
    boolean nonTXTestMode = Boolean.getBoolean(SanityManager.TEST_MODE_NON_TX)
        || Boolean.parseBoolean(System.getenv(SanityManager.TEST_MODE_NON_TX));
    if (nonTXTestMode) {
      System.out.println("Non-tx test mode.");
      conn.setAutoCommit(false);
      conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
    }
    Logger logger = getLogger();
    if (logger != null) {
      logger.info("TestUtil.getConnection::Autocommit is " + conn.getAutoCommit());
    }
    */
    // Read the flag for deleting persistent files only during boot up
    if (jdbcConn == null || jdbcConn.isClosed()) {
      jdbcConn = conn;
      currentUserName = props.getProperty(Attribute.USERNAME_ATTR);
      currentUserName = currentUserName == null ? props
          .getProperty(Attribute.USERNAME_ALT_ATTR) : currentUserName;
      currentUserPassword = props.getProperty(Attribute.PASSWORD_ATTR);
    }
    return conn;
  }

  public static Connection getNetConnection(int port, String urlSuffix,
      Properties props) throws SQLException {
    return getNetConnection(null, port, urlSuffix, props);
  }

  public static synchronized Connection getNetConnection(String host, int port,
      String urlSuffix, Properties props) throws SQLException {
    loadNetDriver();
    if (urlSuffix == null) {
      if ((props == null || props.size() == 0) && currentUserName != null) {
        // set the user/password as the current one
    	// Don't add leading slash as getNetProtocol() below adds a slash
        urlSuffix = (";user=" + currentUserName + ";password="
            + currentUserPassword + ';');
      }
      else {
        urlSuffix = "";
      }
    }
    else if (urlSuffix.length() > 0 && urlSuffix.charAt(0) == '/') {
      // Trim leading slash
      urlSuffix = urlSuffix.substring(1,urlSuffix.length());
    }

    // set the common test properties
    props = doCommonSetup(props);

    final Connection conn;
    try {
      if (host == null) {
        host = "localhost";
      }
      conn = DriverManager.getConnection(
          getNetProtocol(host, port) + urlSuffix, getNetProperties(props));

      /*
      boolean nonTXTestMode = (Boolean.getBoolean(SanityManager.TEST_MODE_NON_TX)
          || Boolean.parseBoolean(System.getenv(SanityManager.TEST_MODE_NON_TX))); 
      if (nonTXTestMode) {
        conn.setAutoCommit(false);
        conn.setTransactionIsolation(Connection.TRANSACTION_NONE);
      }
      */
      return conn;
    } catch (RuntimeException e) {
      throw new AssertionError(e);
    }
  }

  public static void setRandomUserName() {
    currentUserName = GemFireXDUtils.getRandomString(true);
    currentUserPassword = currentUserName;
  }

  public static boolean deleteDir(File dir) {
    if (!dir.exists()) return false;
    deleteDirContents(dir);
    return deleteDirOnly(dir);
  }

  protected static void deleteDirContents(final File dir) {
    if (!dir.exists()) return;
    final File[] files = dir.listFiles();
    if (files != null) {
      for (File f : files) {
        if (f.isDirectory()) {
          deleteDirContents(f);
        }
        deleteDirOnly(f);
      }
    }
  }

  protected static boolean deleteDirOnly(File dir) {
    int numTries = 10;
    while (numTries-- > 0 && dir.exists()) {
      if (dir.delete()) {
        return true;
      }
      /*
      boolean interrupted = Thread.interrupted();
      try {
        Thread.sleep(200);
      } catch (InterruptedException e) {
        interrupted = true;
      } finally {
        if (interrupted) {
          Thread.currentThread().interrupt();
        }
      }
      */
    }
    return (numTries > 0);
  }

  public static String getCurrentDefaultSchemaName() {
    final String schemaName;
    if (currentUserName != null) {
      schemaName = StringUtil.SQLToUpperCase(currentUserName);
    }
    else {
      schemaName = SchemaDescriptor.STD_DEFAULT_SCHEMA_NAME;
    }
    return schemaName;
  }

  public static PreparedStatement getPreparedStatement(String sql)
      throws SQLException {
    return jdbcConn.prepareStatement(sql);
  }

  public static Statement getStatement() throws SQLException {
    return jdbcConn.createStatement();
  }

  public static Connection getConnection() throws SQLException {
    Properties props = new Properties();
    props.setProperty("mcast-port", "0");
    return getConnection(props);
  }

  /**
   * Loads the appropriate JDBC driver for this environment/framework.
   */
  public static void loadDriver() {
    //
    // The JDBC driver is loaded by loading its class.
    // If you are using JDBC 4.0 (Java SE 6) or newer, JDBC drivers may
    // be automatically loaded, making this code optional.
    //
    // In an embedded environment, any static Derby system properties
    // must be set before loading the driver to take effect.
    //
    //[sb] no need to explicitly load the driver with FabricServer api.
//    loadDriverClass(getDriver());
  }

  /**
   * Loads the derby network JDBC driver for this environment/framework.
   */
  public static void loadNetDriver() {
    //
    // The JDBC driver is loaded by loading its class.
    // If you are using JDBC 4.0 (Java SE 6) or newer, JDBC drivers may
    // be automatically loaded, making this code optional.
    //
    // In an embedded environment, any static Derby system properties
    // must be set before loading the driver to take effect.
    //
    loadDriverClass(getNetDriver());
  }

  /**
   * Loads the appropriate JDBC driver for this environment/framework.
   */
  protected static void loadDriverClass(String driver) {
    try {
      Class.forName(driver).newInstance();
    } catch (ClassNotFoundException cnfe) {
      cnfe.printStackTrace();
      fail("Unable to load the JDBC driver " + driver + ":" + cnfe.getMessage());
    } catch (InstantiationException ie) {
      ie.printStackTrace();
      fail("Unable to instantiate the JDBC driver " + driver + ":"
          + ie.getMessage());
    } catch (IllegalAccessException iae) {
      iae.printStackTrace();
      fail("Not allowed to access the JDBC driver " + driver + ":"
          + iae.getMessage());
    }
  }

  public static void loadDerbyDriver() throws Exception {
    Class<?> autoDriverBaseClass = Class.forName(
        "org.apache.derby.jdbc.AutoloadedDriver");
    Driver autoDriver;
    try {
      autoDriver = (Driver)Class.forName(
          "org.apache.derby.jdbc.AutoloadedDriver40").newInstance();
    } catch (Throwable t) {
      autoDriver = (Driver)autoDriverBaseClass.newInstance();
    }
    Method m = autoDriverBaseClass.getDeclaredMethod("registerMe",
        autoDriverBaseClass);
    m.setAccessible(true);
    m.invoke(null, autoDriver);
  }

  public static void shutDown() throws SQLException {
    shutDown(null);
  }

  public static void shutDown(Properties shutdownProperties)
      throws SQLException {
    shutDown(getProtocol(), shutdownProperties);
  }

  public static synchronized void clearStatics() {
    currentTest = null;
    currentTestClass = null;
    currentUserName = null;
    currentUserPassword = null;
    bootUserName = null;
    bootUserPassword = null;
    isTransactional = false;
    // clear any gemfire/gemfirexd system properties
    final Properties sysProps = System.getProperties();
    final HashSet<String> keysToRemove = new HashSet<String>();
    // top-level synchronization ensures no concurrent modification
    synchronized (sysProps) {
      final Enumeration<?> propNames = sysProps.propertyNames();
      while (propNames.hasMoreElements()) {
        final Object prop = propNames.nextElement();
        final String key = prop.toString();
        if (!key.startsWith("gemfire.DUnitLauncher")
            && !initSysProperties.contains(key)
            && (key.startsWith(DistributionConfig.GEMFIRE_PREFIX)
            || key.startsWith(GfxdConstants.GFXD_PREFIX)
            || key.startsWith(GfxdConstants.GFXD_CLIENT_PREFIX)
            || key.startsWith("javax.net.ssl."))) {
          keysToRemove.add(key);
        }
      }
    }
    for (String key : keysToRemove) {
      System.clearProperty(key);
    }
  }

  private static synchronized void shutDown(String protocol,
      Properties shutdownProperties) throws SQLException {
    try {
      for (Map.Entry<String, Integer> exEntry : expectedExceptions.entrySet()) {
        if ((exEntry.getValue() & 0x01) > 0) {
          addLogString("<ExpectedException action=remove>" + exEntry.getKey()
              + "</ExpectedException>");
        }
      }
      expectedExceptions.clear();
      SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(false);
      final Connection conn = jdbcConn;
      if (conn != null) {
        /* (hangs in HeapThresholdDUnit)
        if (!conn.isClosed()) {
          try {
            conn.close();
          } catch (SQLException sqle) {
            // ignore any exceptions at this point
          }
        }
        */
        jdbcConn = null;
      }
      if (shutdownProperties == null) {
        shutdownProperties = new Properties();
      }
      if (bootUserName != null) {
        shutdownProperties.setProperty("user", bootUserName);
        shutdownProperties.setProperty("password", bootUserPassword);
        getLogger().info(
            "shutting down with " + bootUserName + " and boot password "
                + bootUserPassword);
      }
      else if (currentUserName != null) {
        shutdownProperties.setProperty("user", currentUserName);
        if (currentUserPassword != null) {
          shutdownProperties.setProperty("password", currentUserPassword);
        }
        getLogger().info(
            "shutting down with " + currentUserName + " and password "
                + currentUserPassword);
      }

      // Stop any network server first
      stopNetServer();
      final FabricService service = FabricServiceManager
          .currentFabricServiceInstance();
      if (service != null) {
        service.stop(shutdownProperties);
        getLogger().info("Fabric Server shutdown status " + service.status());
      } else if (GemFireStore.getBootingInstance() != null) {
        DriverManager.getConnection(getProtocol() + ";shutdown=true",
            shutdownProperties);
      }

    } catch (SQLException se) {
      if (((se.getErrorCode() == 50000) && ("XJ015".equals(se.getSQLState())))) {
        // we got the expected exception
      }
      else {
        // if the error code or SQLState is different, we have
        // an unexpected exception (shutdown failed)
        throw se;
      }
    }
  }

  public static void dropAllUsers(Connection conn) throws Exception {
    Statement stmt = conn.createStatement();
    stmt.execute("call sys.show_users()");
    PreparedStatement pstmt = null;
    try (ResultSet rs = stmt.getResultSet()) {
      while (rs.next()) {
        if (rs.getString(2).equalsIgnoreCase("USER")) {
          if (pstmt == null) {
            pstmt = conn.prepareStatement("call sys.drop_user(?)");
          }
          pstmt.setString(1, rs.getString(1));
          pstmt.execute();
        }
      }
    } finally {
      if (pstmt != null) {
        pstmt.close();
      }
    }
  }

  // End: Utility methods for managing JDBC connections

  // SQL Execution helper methods

  public static String convertByteArrayToString(byte[] byteArray) {
    if (byteArray == null) return "null";
    StringBuilder sb = new StringBuilder();
    sb.append('{');
    for (byte b : byteArray) {
      sb.append(b + ", ");
    }
    sb.deleteCharAt(sb.lastIndexOf(","));
    sb.append('}');
    return sb.toString();
  }

  public static final boolean compareResults(ResultSet expected,
      ResultSet rs, boolean ignoreTypeInfo) throws Exception {
    // Convert the ResultSet to an XML Element
    Element resultElement = Misc.resultSetToXMLElement(rs, true,
        ignoreTypeInfo);
    Element expectedElement = Misc.resultSetToXMLElement(expected, true,
        ignoreTypeInfo);
    Map<String, Integer> resultMap = TestUtil
        .xmlElementToFrequencyMap(resultElement);
    Map<String, Integer> expectedResultMap = TestUtil
        .xmlElementToFrequencyMap(expectedElement);
    String resultStr = Misc.serializeXML(resultElement);
    String expectedStr = Misc.serializeXML(expectedElement);

    if (TestUtil.compareFrequencyMaps(expectedResultMap, resultMap)) {
      return true;
    } else {
      getLogger().info(
          "sqlExecuteVerifyText: The result XML is:\n" + resultStr);
      getLogger().info(
          "sqlExecuteVerifyText: The expected XML is:\n" + expectedStr);
      return false;
    }
  }

  public static final void validateResults(ResultSet derbyRS, ResultSet gfxdRS,
      boolean ignoreTypeInfo) throws Exception {
    if (!compareResults(derbyRS, gfxdRS, ignoreTypeInfo)) {
      fail("Expected string in derby does not "
          + "match the result from GemFireXD.");
    }
  }

  public static final void validateResults(Statement derbyStmt, Statement stmt,
      String validationQuery, boolean ignoreTypeInfo) throws Exception {

    ResultSet derbyRS, gfxdRS;
    if (derbyStmt instanceof PreparedStatement) {
      derbyRS = ((PreparedStatement)derbyStmt).executeQuery();
    } else {
      derbyRS = derbyStmt.executeQuery(validationQuery);
    }
    if (stmt instanceof PreparedStatement) {
      gfxdRS = ((PreparedStatement)stmt).executeQuery();
    } else {
      gfxdRS = stmt.executeQuery(validationQuery);
    }
    validateResults(derbyRS, gfxdRS, ignoreTypeInfo);
  }

  /**
   * Execute the given SQL string without any checking for results. The optional
   * <code>usePrepStmt</code> specifies whether to use {@link PreparedStatement}
   * or to use {@link Statement} for SQL execution.
   */
  public static void sqlExecute(String jdbcSQL, Boolean usePrepStmt)
      throws SQLException {
    sqlExecute(jdbcSQL, usePrepStmt, Boolean.FALSE);
  }

  /**
   * Execute the given SQL string without any checking for results but consuming
   * all if required. The optional <code>usePrepStmt</code> specifies whether to
   * use {@link PreparedStatement} or to use {@link Statement} for SQL
   * execution.
   */
  public static void sqlExecute(String jdbcSQL, Boolean usePrepStmt,
      Boolean consumeResults) throws SQLException {
    setupConnection();
    getLogger().info(
        "For user '" + jdbcConn.getMetaData().getUserName() + '['
            + currentUserName + "]' executing SQL: " + jdbcSQL);
    if (usePrepStmt.booleanValue()) {
      PreparedStatement stmt = jdbcConn.prepareStatement(jdbcSQL);
      try {
        if (stmt.execute() && consumeResults.booleanValue()) {
          int numResults = 0;
          // consume all results for streaming case
          ResultSet rs = stmt.getResultSet();
          while (rs.next()) {
            ++numResults;
          }
          getLogger().info(
              "Got numResults=" + numResults + " for SQL: " + jdbcSQL);
        }
      } finally {
        stmt.close();
      }
    }
    else {
      Statement stmt = jdbcConn.createStatement();
      try {
        if (stmt.execute(jdbcSQL) && consumeResults.booleanValue()) {
          int numResults = 0;
          // consume all results for streaming case
          ResultSet rs = stmt.getResultSet();
          while (rs.next()) {
            ++numResults;
          }
          getLogger().info(
              "Got numResults=" + numResults + " for SQL: " + jdbcSQL);
        }
      } finally {
        stmt.close();
      }
    }
  }

  /**
   * Execute the given SQL string checking that the execution should return at
   * least one result i.e. either at least one row in {@link ResultSet} for
   * queries or at least one affected row for updates. The optional
   * <code>usePrepStmt</code> specifies whether to use {@link PreparedStatement}
   * or to use {@link Statement} for SQL execution.
   */
  public static int sqlExecuteVerify(String jdbcSQL, Boolean usePrepStmt)
      throws SQLException {
    setupConnection();
    getLogger().info(
        "For user '" + jdbcConn.getMetaData().getUserName() + '['
            + currentUserName + "]' executing SQL with verification: "
            + jdbcSQL);
    Statement stmt;
    boolean resultType;
    if (usePrepStmt.booleanValue()) {
      stmt = jdbcConn.prepareStatement(jdbcSQL);
      resultType = ((PreparedStatement)stmt).execute();
    }
    else {
      stmt = jdbcConn.createStatement();
      resultType = stmt.execute(jdbcSQL);
    }
    int count = 0;
    try {
      if (resultType) {
        ResultSet rs = stmt.getResultSet();
        while (rs.next()) {
          ++count;
        }
      }
      else {
        count = stmt.getUpdateCount();
      }
    } finally {
      stmt.close();
    }
    getLogger().info(
        "sqlExecuteVerify: got the number of changes/results as: " + count);
    // Assert.assertTrue("Expected at least one result/change", count > 0);
    return count;
  }

  /**
   * Execute the given SQL string verifying the obtained {@link ResultSet}
   * against the provided XML string. The XML string can be either provided as
   * name of the file (with "id" attribute -- see below in
   * <code>resultSetID</code>) or can be provided inline. The optional
   * <code>usePrepStmt</code> specifies whether to use {@link PreparedStatement}
   * or to use {@link Statement} for SQL execution. The verification can be
   * either ordered (i.e. the order in results should match exactly as given in
   * the XML file) or unordered. The default is to use unordered comparison
   * which can be overriden by setting the "ordered" attribute in the
   * "resultSet" element in the XML to either "true" or "false". For scalar
   * results just give the goldenTextFile as null and resultSetID as the scalar
   * result.
   * 
   * @param jdbcSQL
   *          the SQL string to be executed
   * @param goldenTextFile
   *          Either name of the file containing the XML string to be used for
   *          comparison against the obtained ResultSet (or update count), or
   *          the XML string itself starting at the appropriate "resultSet"
   *          element. The second case is assumed when <code>resultSetID</code>
   *          is null.
   * @param resultSetID
   *          The "id" attribute of the "resultElement" element in the XML file.
   *          This "resultSet" element is the one that is used for comparison.
   *          If this is null then the <code>goldenTextFile</code> is assumed to
   *          contain the XML string starting at the appropriate "resultSet"
   *          element. It can also contain the scalar result string in case
   *          <code>goldenTextFile</code> argument is null.
   * @param usePrepStmt
   *          true if {@link PreparedStatement} should be used for SQL
   *          execution; false to use {@link Statement}
   * @param checkTypeInfo
   *          if true then the type information (obtained using
   *          {@link Class#getName()} of each field of the ResultSet is also
   *          compared against that specified in the XML string
   */
  public static void sqlExecuteVerifyText(String jdbcSQL,
      String goldenTextFile, String resultSetID, Boolean usePrepStmt,
      Boolean checkTypeInfo) throws SQLException, IOException,
      ParserConfigurationException, SAXException, TransformerException {
    setupConnection();
    Statement stmt;
    boolean resultType;
    if (usePrepStmt.booleanValue()) {
      stmt = jdbcConn.prepareStatement(jdbcSQL);
      resultType = ((PreparedStatement)stmt).execute();
    }
    else {
      stmt = jdbcConn.createStatement();
      resultType = stmt.execute(jdbcSQL);

    }
    verifyResults(resultType, stmt, checkTypeInfo.booleanValue(),
        goldenTextFile, resultSetID);
  }

  /**
   * The guts of
   * {@link #sqlExecuteVerifyText(String, String, String, Boolean, Boolean)}.
   * 
   * @param resultType
   *          true if the first result is a ResultSet object; false if it is an
   *          update count or there are no results
   * @param stmt
   *          the {@link Statement} or {@link PreparedStatement} used for
   *          execution
   * @param checkTypeInfo
   *          if true then the type information (obtained using
   *          {@link Class#getName()} of each field of the ResultSet is also
   *          compared against that specified in the XML string
   * @param goldenTextFile
   *          Either name of the file containing the XML string to be used for
   *          comparison against the obtained ResultSet (or update count), or
   *          the XML string itself starting at the appropriate "resultSet"
   *          element. The second case is assumed when <code>resultSetID</code>
   *          is null. If this is null then the result is assumed to be scalar
   *          and <code>resultSetID</code> argument should contain the scalar
   *          result string.
   * @param resultSetID
   *          The "id" attribute of the "resultElement" element in the XML file.
   *          This "resultSet" element is the one that is used for comparison.
   *          If this is null then the <code>goldenTextFile</code> is assumed to
   *          contain the XML string starting at the appropriate "resultSet"
   *          element. It can also contain the scalar result string in case
   *          <code>goldenTextFile</code> argument is null.
   * 
   * @see #sqlExecuteVerifyText(String, String, String, Boolean, Boolean)
   */
  public static void verifyResults(boolean resultType, Statement stmt,
      boolean checkTypeInfo, String goldenTextFile, String resultSetID)
      throws SQLException, IOException, ParserConfigurationException,
      SAXException, TransformerException {
    try {
      if (resultType) {
        boolean comparisonResult;
        String resultStr = null;
        String expectedStr = null;
        // if goldenTextFile is null then assume scalar result
        if (goldenTextFile == null) {
          ResultSet rs = stmt.getResultSet();
          expectedStr = resultSetID;
          if ((comparisonResult = rs.next())) {
            resultStr = String.valueOf(rs.getObject(1));
            comparisonResult = expectedStr.equals(resultStr);
            assertFalse("Expected exactly one scalar result: "
                + expectedStr + " already read: " + resultStr, rs.next());
          }
          else {
            // for no result, check that expected resultSetID is also null
            comparisonResult = (resultSetID == null);
          }
          rs.close();
        }
        else {
          // Convert the ResultSet to an XML Element
          Element resultElement = Misc.resultSetToXMLElement(stmt
              .getResultSet(), checkTypeInfo, false);
          Element expectedElement = readResultSetXMLFromFile(goldenTextFile,
              resultSetID);
          // Use of "ordered" attribute for resultSet element specifies whether
          // or not the ResultSet is expected to be in exactly the same order
          // as specified in the XML.
          if ("true".equals(expectedElement.getAttribute("ordered"))) {
            resultStr = Misc.serializeXML(resultElement);
            expectedElement.removeAttribute("ordered");
            expectedStr = Misc.serializeXML(expectedElement);

            comparisonResult = expectedStr.equals(resultStr);
          }
          else {
            resultStr = Misc.serializeXML(resultElement);
            expectedStr = Misc.serializeXML(expectedElement);
            getLogger().info(
                "sqlExecuteVerifyText: Verifying for result " + "XML:\n"
                    + resultStr + "\nexpectedStr:\n" + expectedStr);
            Map<String, Integer> resultMap;
            Map<String, Integer> expectedResultMap;
            resultMap = xmlElementToFrequencyMap(resultElement);
            expectedResultMap = xmlElementToFrequencyMap(expectedElement);

            comparisonResult = compareFrequencyMaps(expectedResultMap,
                resultMap);
          }
        }
        if (!comparisonResult) {
          getLogger().info(
              "sqlExecuteVerifyText: The result XML is:\n" + resultStr);
          System.out.println("sqlExecuteVerifyText: The result XML is:\n" + resultStr);
          getLogger().info(
              "sqlExecuteVerifyText: The expected XML is:\n" + expectedStr);
          fail("Expected string in golden file does not "
              + "match the result.");
        }
        else {
          getLogger().info(
              "sqlExecuteVerifyText: Verified for result XML:\n" + resultStr);
        }
      }
      else {
        String result;
        String expectedResult;
        result = String.valueOf(stmt.getUpdateCount());
        BufferedReader reader = new BufferedReader(new FileReader(
            goldenTextFile));
        try {
          char[] expectedChars = new char[result.length()];
          int numRead = reader.read(expectedChars, 0, expectedChars.length);
          assertEquals(
              "Number of characters in golden file does not match.", numRead,
              expectedChars.length);
          assertEquals("Expected the golden file to end.", -1, reader
              .read());
          expectedResult = new String(expectedChars);
        } finally {
          reader.close();
        }
        assertEquals(
            "Expected number of results does not match the obtained number.",
            expectedResult, result);
      }
    } finally {
      stmt.close();
    }
  }

  // End: SQL Execution helper methods

  /**
   * Verify the {@link RegionAttributes} of the given schema/table against the
   * given {@link RegionAttributes} object. The arguments types are specified as
   * {@link Object} to easily use them from {@link DistributedSQLTestBase} when
   * either of them is passed as null.
   * 
   * @param schemaName
   *          Name of the schema as a String, or null to use default schema.
   * @param tableName
   *          Name of the table as a String, or null if schema is to be tested.
   *          Either the schemaName argument or tableName should be non-null
   * @param propsStr
   *          The {@link RegionAttributes} object to be tested against. This can
   *          be null to indicate that the region for specified schema/table
   *          should not exist. Typically this object will be created using
   *          {@link RegionAttributesCreation} class, and invoking
   *          {@link RegionAttributesCreation#setAllHasFields(boolean)} on the
   *          object.
   */
  public static void verifyRegionProperties(Object schemaName,
      Object tableName, Object propsStr) {
    String fullTableName = getFullTableName(schemaName, tableName);
    getLogger().info(
        "For current user '" + currentUserName
            + "' verifying region properties for table " + fullTableName);
    Region<?, ?> tableRegion = Misc.getRegionForTable(fullTableName, false);
    // Null propsStr indicates that region should have been destroyed
    if (propsStr != null) {
      assertFalse("Expected the region for [" + fullTableName
          + "] to exist.", tableRegion == null || tableRegion.isDestroyed());      
      String tableRegionAttrsStr = regionAttributesToXML(tableRegion
          .getAttributes());
      getLogger().info(
          "verifyRegionProperties: The expected properties are:\n"
              + propsStr.toString());
      getLogger().info(
          "verifyRegionProperties: The result properties are:\n"
              + tableRegionAttrsStr);
      assertEquals("Expected the region attributes to match.", propsStr
          .toString(), tableRegionAttrsStr);
    }
    else {
      assertTrue("Expected the region for [" + fullTableName
          + "] to be destroyed.", tableRegion == null
          || tableRegion.isDestroyed());
      getLogger().info(
          "verifyRegionProperties: Got the region for [" + fullTableName
              + "] as destroyed as expected.");
    }
  }

  /**
   * Get the full table name given the schema name and table name. Schema name
   * as null indicates the default schema while table name as null indicates
   * that full name for only schema is required.
   */
  public static String getFullTableName(Object schemaName, Object tableName) {
    if (schemaName == null) {
      schemaName = getCurrentDefaultSchemaName();
    }
    String tableSubPath = "";
    if (tableName != null) {
      tableSubPath = "." + tableName.toString().toUpperCase();
    }
    return (schemaName.toString() + tableSubPath);
  }

  /** get an XML representation for the RegionAttributes */
  public static String regionAttributesToXML(RegionAttributes<?, ?> attrs) {
    if (attrs == null) {
      return null;
    }
    
    if (attrs.getPartitionAttributes() != null) {
      try {
        ((PartitionAttributesImpl) attrs.getPartitionAttributes()).computeLocalMaxMemory();
      } catch (IllegalStateException ise) {
        // Just leave local memory as it is
      }
    }

    UserSpecifiedRegionAttributes<?, ?> usra = null;
    // Asif: We also get DistributedRegion as RegionAttribute so
    // we cannot always know if the disk data was set or not
    // what can be done?
    if (attrs instanceof UserSpecifiedRegionAttributes) {
      usra = (UserSpecifiedRegionAttributes<?, ?>)attrs;
      //getLogger().info("UserSpecified region attributes = "+attrs);
    }
    else {
//      getLogger().info("Not use specified region attribs. class = "+attrs.getClass());
//      getLogger().info(attrs.toString());
    }
    CacheCreation cache = new CacheCreation();
    RegionCreation rgn = (RegionCreation)cache.createRegion("_ATTRS_TMP_TABLE",
        attrs);
    RegionAttributesCreation rac = (RegionAttributesCreation)rgn
        .getAttributes();
    if (usra != null) {
      rac.setHasDiskDirs(usra.hasDiskDirs());
      rac.setHasDiskWriteAttributes(usra.hasDiskWriteAttributes());
    }
    else {
      rac.setHasDiskDirs(false);
      rac.setHasDiskWriteAttributes(false);
    }
    StringWriter sw = new StringWriter();
    PrintWriter pw = new PrintWriter(sw);
    CacheXmlGenerator.generate(cache, pw);
    pw.close();
    return sw.getBuffer().toString();
  }

  /**
   * Create a "resultSet" XML Element from the given file that has the given
   * "id" attribute. If the <code>resultSetID</code> argument is null then it is
   * assumed that the given file string actually contains the required XML
   * string itself. No DTD validation is performed for the latter case when XML
   * string has been provided.
   */
  public static Element readResultSetXMLFromFile(String xmlFile,
      String resultSetID) throws IOException, SAXException,
      ParserConfigurationException, UnsupportedEncodingException {
    // If resultSetID is null then it implies that xmlFile contains the XML
    // string itself
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setIgnoringComments(true);
    factory.setIgnoringElementContentWhitespace(true);
    Element expectedElement;
    if (resultSetID == null) {
      DocumentBuilder builder = factory.newDocumentBuilder();
      ByteArrayInputStream bis = new ByteArrayInputStream(xmlFile
          .getBytes("UTF-8"));
      expectedElement = (Element)builder.parse(bis).getFirstChild();
    }
    else {
      factory.setValidating(true);
      DocumentBuilder builder = factory.newDocumentBuilder();
      XmlErrorHandler errorHandler = new XmlErrorHandler(xmlFile);
      builder.setErrorHandler(errorHandler);
      Document doc = builder.parse(xmlFile);
      expectedElement = doc.getElementById(resultSetID);
      expectedElement.removeAttribute("id");
    }
    return expectedElement;
  }

  /**
   * Get a {@link Logger} object that can be used for both dunit and junit
   * tests.
   */
  public static Logger getLogger() {
    return globalLogger;
  }

  public static void fail(String message) {
    getLogger().error(message);
    TestCase.fail(message);
  }

  public static void fail(String message, Throwable t) {
    final StringPrintWriter pw = new StringPrintWriter();
    pw.append(message).append(": ");
    t.printStackTrace(pw);
    fail(pw.toString());
  }

  /** add a log string to either the gemfirexd logs */
  public static void addLogString(final String logStr) {
    SanityManager.DEBUG_PRINT("ExpectedEx", logStr);
    System.out.println(logStr);
  }

  /**
   * Add a single expected exception string to the gemfirexd logs. The
   * <code>exceptionClass</code> can be either the {@link Class} object or fully
   * qualified class name or any string to match against suspect strings that
   * needs to be ignored.
   */
  public static void addExpectedException(final Object exceptionClass) {
    addExpectedException(new Object[] { exceptionClass });
  }

  /**
   * Add expected exceptions string to the gemfirexd logs. The
   * <code>exceptionClasses</code> can be either the {@link Class} object or
   * fully qualified class name.
   */
  public static void addExpectedException(final Object[] exceptionClasses) {
    for (Object exObj : exceptionClasses) {
      String exStr = GemFireXDUtils.getExpectedExceptionString(exObj, false);
      addLogString(exStr);
      Integer flag = expectedExceptions.get(exStr);
      if (flag != null) {
        flag |= 0x01;
      }
      else {
        flag = 0x01;
      }
      expectedExceptions.put(exStr, flag);
    }
  }

  public static void addExpectedExceptions(Object[] exceptionClasses) {
    addExpectedException(exceptionClasses);
  }

  /**
   * Add a single expected exception string with <code>remove</code> tag to the
   * gemfirexd logs. The <code>exceptionClass</code> can be either the
   * {@link Class} object or fully qualified class name.
   */
  public static void removeExpectedException(final Object exceptionClass) {
    removeExpectedException(new Object[] { exceptionClass });
  }

  /**
   * Add expected exceptions string with <code>remove</code> tag to the
   * gemfirexd logs. The <code>exceptionClasses</code> can be either the
   * {@link Class} object or fully qualified class name.
   */
  public static void removeExpectedException(final Object[] exceptionClasses) {
    for (Object exObj : exceptionClasses) {
      String exStr = GemFireXDUtils.getExpectedExceptionString(exObj, true);
      addLogString(exStr);
      Integer flag = expectedExceptions.get(exStr);
      if (flag != null) {
        flag ^= 0x01;
        if (flag == 0x0) {
          expectedExceptions.remove(exStr);
        }
        else {
          expectedExceptions.put(exStr, flag);
        }
      }
    }
  }

  public static void removeExpectedExceptions(final Object[] exceptionClasses) {
    removeExpectedException(exceptionClasses);
  }

  public static PartitionAttributesImpl getPartitionAttributes(String tableName) {
    Region<?, ?> custRegion = Misc.getRegionForTable(StringUtil
        .SQLToUpperCase(tableName), true);
    PartitionAttributesImpl pattrs = (PartitionAttributesImpl)custRegion
        .getAttributes().getPartitionAttributes();
    assertNotNull("Expected partitioned attributes to be non-null", pattrs);
    assertTrue("Expected resolver to be GfxdPartitionResolver", pattrs
        .getPartitionResolver() instanceof GfxdPartitionResolver);
    return pattrs;
  }

  public static void checkServerGroups(String tableName, String... serverGroups) {
    Region<?, ?> region = Misc.getRegionForTable(StringUtil
        .SQLToUpperCase(tableName), true);
    SortedSet<String> actualServerGroups = ((GemFireContainer)region.getUserAttribute())
        .getDistributionDescriptor().getServerGroups();
    if (serverGroups == null) {
      assertTrue("expected target server groups to be null",
          actualServerGroups == null || actualServerGroups.size() == 0);
      return;
    }
    assertEquals(serverGroups.length, actualServerGroups.size());
    for (String serverGroup : serverGroups) {
      assertTrue(actualServerGroups.contains(StringUtil
          .SQLToUpperCase(serverGroup)));
    }
  }

  public static GfxdPartitionResolver checkColocation(String tableName,
      String targetSchema, String targetTable) {
    Region<?, ?> region = Misc.getRegionForTable(StringUtil
        .SQLToUpperCase(tableName), true);
    GemFireContainer container = (GemFireContainer)region.getUserAttribute();
    PartitionAttributes<?, ?> pattrs = container.getRegionAttributes()
        .getPartitionAttributes();
    // first check using DistributionDescriptor
    DistributionDescriptor dd = container.getDistributionDescriptor();
    String targetRegionPath = null;
    if (targetTable != null && targetTable.length() > 0) {
      targetTable = Misc.getFullTableName(
          StringUtil.SQLToUpperCase(targetSchema),
          StringUtil.SQLToUpperCase(targetTable), null);
      targetRegionPath = "/" + targetTable.replace('.', '/');
    }
    assertEquals("Failure in checking colocation of tables: ",
        targetRegionPath, dd.getColocateTableName());
    // then using region attributes
    assertEquals("Failure in checking colocation of regions: ",
        targetRegionPath, pattrs.getColocatedWith());
    return (GfxdPartitionResolver)pattrs.getPartitionResolver();
  }
  /**
   * Utility class to store a column name and value pair using for creating a
   * frequency map of the rows of a result set.
   */
  private static class ColumnNameValue implements Comparable<ColumnNameValue> {

    private String colName;

    private final String colValue;

    public ColumnNameValue(String name, String value) {
      this.colName = name;
      this.colValue = value;
    }

    @Override
    public int compareTo(ColumnNameValue other) {
      if (other == null) {
        return -1;
      }
      int res = this.colName.compareTo(other.colName);
      if (res != 0) {
        return res;
      }
      return this.colValue.compareTo(other.colValue);
    }

    @Override
    public boolean equals(Object obj) {
      if (obj == null || !(obj instanceof ColumnNameValue)) {
        return false;
      }
      ColumnNameValue other = (ColumnNameValue)obj;
      return (this.colName.equals(other.colName) && this.colValue
          .equals(other.colValue));
    }

    @Override
    public int hashCode() {
      return (this.colName.hashCode() ^ this.colValue.hashCode());
    }
  }

  /**
   * Convert a XML Element that represents a SQL ResultSet to a frequency map
   * for unordered comparisons. This map contains mapping of the XML string of a
   * row to number of occurrences of that row in the ResultSet.
   */
  public static Map<String, Integer> xmlElementToFrequencyMap(
      Element resultSetElement) throws SQLException, IOException,
      TransformerException {
    NodeList rowList = resultSetElement.getChildNodes();
    Map<String, Integer> frequencyMap = new HashMap<String, Integer>();
    for (int index = 0; index < rowList.getLength(); ++index) {
      Element rowElement = (Element)rowList.item(index);
      SortedMap<ColumnNameValue, Element> rowMap = new TreeMap<ColumnNameValue, Element>();
      Map<ColumnNameValue, Integer> repeatedColumns = new HashMap<ColumnNameValue, Integer>();
      NodeList fieldList = rowElement.getChildNodes();
      while (fieldList.getLength() > 0) {
        Element fieldElement = (Element)fieldList.item(0);
        String columnName = fieldElement.getAttribute("name");
        if (columnName != null) {
          ColumnNameValue col = new ColumnNameValue(columnName, fieldElement
              .getTextContent());
          if (repeatedColumns.containsKey(col)) {
            Integer suffix = repeatedColumns.get(col);
            repeatedColumns.put(col, Integer.valueOf(suffix.intValue() + 1));
            col.colName += ("." + suffix.toString());
            rowMap.put(col, fieldElement);
          }
          else {
            repeatedColumns.put(col, Integer.valueOf(1));
            rowMap.put(col, fieldElement);
          }
        }
        rowElement.removeChild(fieldElement);
      }
      for (Map.Entry<ColumnNameValue, Element> row : rowMap.entrySet()) {
        rowElement.appendChild(row.getValue());
      }
      String rowXML = Misc.serializeXML(rowElement);
      Object numRowInstances = frequencyMap.get(rowXML);
      if (numRowInstances == null) {
        frequencyMap.put(rowXML, Integer.valueOf(1));
      }
      else {
        frequencyMap.put(rowXML, Integer.valueOf(((Integer)numRowInstances)
            .intValue() + 1));
      }
    }
    return frequencyMap;
  }

  /**
   * Compare two frequency maps obtained using
   * {@link #xmlElementToFrequencyMap(Element)} method. This effectively results
   * in an unordered comparison of the ResultSet objects that were used to
   * create the frequency maps.
   */
  public static boolean compareFrequencyMaps(Map<String, Integer> expectedMap,
      Map<String, Integer> resultMap) {
    if (expectedMap.size() != resultMap.size()) {
      getLogger().info(
          "Expected size of result set: " + expectedMap.size()
              + "; obtained size: " + resultMap.size());
      return false;
    }
    for (Map.Entry<String, Integer> entry : expectedMap.entrySet()) {
      Integer resultValue = resultMap.get(entry.getKey());
      if (!entry.getValue().equals(resultValue)) {
        getLogger().info(
            "Expected entry [" + entry.getValue() + "] for row ["
                + entry.getKey() + "] but got [" + resultValue + ']');
        return false;
      }
    }
    return true;
  }

  /**
   * An implementation of {@link ErrorHandler} class used while parsing a given
   * XML file or XML string.
   * 
   * @author swale
   * @since 6.0
   */
  public static class XmlErrorHandler implements ErrorHandler {

    private final String xmlFileName;

    /**
     * Constructor that takes the XML file name as an argument. This is used for
     * logging and in the exceptions that are thrown.
     */
    public XmlErrorHandler(String xmlFileName) {
      this.xmlFileName = xmlFileName;
    }

    /**
     * Throws back the exception with the name of the XML file and the position
     * where the exception occurred.
     */
    @Override
    public void error(SAXParseException exception) throws SAXException {
      throw new SAXParseException("Error while parsing XML at line "
          + exception.getLineNumber() + " column "
          + exception.getColumnNumber() + ": " + exception.getMessage(), null);
    }

    /**
     * Throws back the exception with the name of the XML file and the position
     * where the exception occurred.
     */
    @Override
    public void fatalError(SAXParseException exception) throws SAXException {
      throw new SAXParseException("Fatal error while parsing XML at line "
          + exception.getLineNumber() + " column "
          + exception.getColumnNumber() + ": " + exception.getMessage(), null);
    }

    /**
     * Log the exception with XML filename and the position of exception in the
     * file.
     */
    @Override
    public void warning(SAXParseException exception) throws SAXException {
      getLogger().info(
          "Warning while parsing XML [" + this.xmlFileName + "] at line "
              + exception.getLineNumber() + " column "
              + exception.getColumnNumber() + ": " + exception.getMessage());
    }
  }

  /**
   * This class can be used to test reliably as to what kinds of scans were
   * opened on a table during the execution of a query/update/delete. It uses
   * the {@link GemFireXDQueryObserver} interface to register the index/table
   * scans opened on each table and stores them in a map. Users of this class
   * can add the expected scan types using
   * {@link ScanTypeQueryObserver#addExpectedScanType} and then check for
   * matching types using {@link ScanTypeQueryObserver#checkAndClear}. The
   * special scan type {@link ScanType#NONE} indicates that
   * the mentioned table should not have any scans opened on it (e.g. because
   * the query should be get convertible).
   * 
   * @author swale
   */
  @SuppressWarnings({ "serial", "unchecked", "rawtypes" })
  public static class ScanTypeQueryObserver extends
      GemFireXDQueryObserverAdapter {

    private final Map<String, Object> expectedScanTypes =
      new HashMap<String, Object>();

    private final Map<String, Map<String, ScanType>> foundScanTypes =
      new HashMap<String, Map<String, ScanType>>();

    private final Set<Scan> foundSorters = new HashSet<Scan>();

    /**
     * Add an expected scan type for the given conglomerate name (currently
     * fully qualified name of the table).
     * 
     * @param conglomName
     *          fully qualified name of the table
     * @param scanType
     *          one of the {@link ScanType} enumerations
     */
    public void addExpectedScanType(String conglomName, ScanType scanType) {
      addNewScanType(this.expectedScanTypes, StringUtil
          .SQLToUpperCase(conglomName), null, scanType);
    }

    /**
     * Add an expected scan conglomerate name and type for the given
     * conglomerate name (currently fully qualified name of the table).
     * 
     * @param conglomName
     *          fully qualified name of the table
     * @param scanConglomName
     *          fully qualified name of the conglomerate expected to be scanned
     * @param scanType
     *          one of the {@link ScanType} enumerations
     */
    public void addExpectedScanType(String conglomName, String scanConglomName,
        ScanType scanType) {
      addNewScanType(this.expectedScanTypes,
          StringUtil.SQLToUpperCase(conglomName),
          StringUtil.SQLToUpperCase(scanConglomName), scanType);
    }

    @Override
    public void scanControllerOpened(Object sc, Conglomerate conglom) {
      // ignore calls from management layer
      if (isManagementLayerCall()) {
        return;
      }
      GemFireContainer baseContainer = null;
      String conglomContainerName = null;
      ScanType scanType = null;
      if (sc instanceof MemHeapScanController) {
        baseContainer = ((MemHeap)conglom).getGemFireContainer();
        conglomContainerName = baseContainer.getQualifiedTableName();
        scanType = ScanType.TABLE;
      }
      else if (sc instanceof MemIndexScanController) {
        MemIndex index = (MemIndex)conglom;
        baseContainer = index.getBaseContainer();
        if (sc instanceof Hash1IndexScanController) {
          conglomContainerName = "HASH1INDEX:"
              + baseContainer.getQualifiedTableName();
          scanType = ScanType.HASH1INDEX;
        }
        else {
          conglomContainerName = index.getGemFireContainer()
              .getQualifiedTableName().replaceFirst(":base-table:.*$", "");
          if (sc instanceof GlobalHashIndexScanController) {
            scanType = ScanType.GLOBALHASHINDEX;
          }
          else if (sc instanceof SortedMap2IndexScanController) {
            scanType = ScanType.SORTEDMAPINDEX;
          }
        }
      }
      else if (sc instanceof BackingStoreHashtable) {
        scanType = ScanType.BACKINGSTORE;
        GemFireContainer container = ((MemConglomerate)conglom)
            .getGemFireContainer();
        if (conglom instanceof MemIndex) {
          baseContainer = ((MemIndex)conglom).getBaseContainer();
          conglomContainerName = container.getQualifiedTableName()
              .replaceFirst(":base-table:.*$", "") + ".BACKINGSTORE";
        }
        else {
          baseContainer = container;
          conglomContainerName = container.getQualifiedTableName()
              + ".BACKINGSTORE";
        }
      }
      else if (sc instanceof Scan) {
        this.foundSorters.add((Scan)sc);
        return;
      }
      if (scanType == null) {
        throw new IllegalStateException("Unknown scan controller received: "
            + sc.getClass());
      }
      addNewScanType((Map)this.foundScanTypes,
          baseContainer.getQualifiedTableName(), conglomContainerName, scanType);
    }

    private boolean isManagementLayerCall() {
      StackTraceElement[] stack = Thread.currentThread().getStackTrace();
      for (StackTraceElement frame : stack) {
        final String frameCls = frame.getClassName();
        if ("com.pivotal.gemfirexd.internal.engine.management.impl.InternalManagementService"
            .equals(frameCls)) {
          return true;
        }
      }
      return false;
    }

    public Set<Scan> getSorters() {
      return this.foundSorters;
    }

    public void checkAndClear() {
      for (Map.Entry<String, Object> entry : this.expectedScanTypes.entrySet()) {
        String conglomName = entry.getKey();
        Object expectedScans = entry.getValue();
        Set<Object> expectedScanSet = null;
        Map<?, ?> expectedScanMap = null;
        Map<String, ScanType> gotScans = this.foundScanTypes.get(conglomName);
        if (expectedScans instanceof Set<?>) {
          expectedScanSet = (Set<Object>)expectedScans;
        }
        else {
          expectedScanMap = (Map<?, ?>)expectedScans;
        }
        if ((expectedScanSet != null && expectedScanSet.contains(ScanType.NONE))
            || (expectedScanMap != null && expectedScanMap
                .containsValue(ScanType.NONE))) {
          assertNull("Did not expect to find any scans for conglomerate ["
              + conglomName + "] but got: " + gotScans, gotScans);
          continue;
        }
        assertNotNull("Did not find scans for conglomerate: " + conglomName
            + " [ actual = " + this.foundScanTypes + " ]", gotScans);
        if (expectedScanSet != null) {
          Collection<ScanType> gotScanTypes = gotScans.values();
          // ignore backstore hashtable if not specified in expectedScanSet
          if (gotScanTypes.contains(ScanType.BACKINGSTORE)
              && !expectedScanSet.contains(ScanType.BACKINGSTORE)) {
            expectedScanSet.add(ScanType.BACKINGSTORE);
          }
          if (expectedScanSet.size() != gotScans.size()
              || !expectedScanSet.containsAll(gotScanTypes)) {
            throw new AssertionError("The expected scans [" + expectedScanSet
                + "] does not match with actual [" + gotScans
                + "] for conglomerate: " + conglomName);
          }
        }
        else {
          if (expectedScanMap.size() != gotScans.size()
              || !expectedScanMap.equals(gotScans)) {
            throw new AssertionError("The expected scans [" + expectedScanMap
                + "] does not match with actual [" + gotScans
                + "] for conglomerate: " + conglomName);
          }
        }
      }
      clear();
    }

    public void clear() {
      this.expectedScanTypes.clear();
      this.foundScanTypes.clear();
      this.foundSorters.clear();
    }

    public void clearExpectedScanTypes() {
      this.expectedScanTypes.clear();
    }

    private void addNewScanType(Map<String, Object> indexMap,
        String conglomName, String scanConglom, ScanType expectedScan) {
      Object indexes = indexMap.get(conglomName);
      if (indexes == null) {
        if (scanConglom == null) {
          indexes = new HashSet<ScanType>();
        }
        else {
          indexes = new HashMap<String, ScanType>();
        }
        indexMap.put(conglomName, indexes);
      }
      if (scanConglom == null) {
        ((HashSet<ScanType>)indexes).add(expectedScan);
      }
      else {
        ((HashMap<String, ScanType>)indexes).put(scanConglom, expectedScan);
      }
    }
  }

  /**
   * Enumeration for different scan types used by {@link ScanTypeQueryObserver}.
   * 
   * @author swale
   */
  public static enum ScanType {
    NONE, TABLE, HASH1INDEX, SORTEDMAPINDEX, GLOBALHASHINDEX, BACKINGSTORE
  }

  protected static NetworkInterface netServer = null;

  public static FabricService getFabricService() {
    FabricService service = FabricServiceManager.currentFabricServiceInstance();
    if (service == null) {
      service = FabricServiceManager.getFabricServerInstance();
    }
    return service;
  }

  public static Connection startNetserverAndGetLocalNetConnection()
      throws Exception {
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    // Use this VM as the network client
    TestUtil.loadNetDriver();
    startNetServer(netPort, null);
    return getNetConnection(netPort, null, null);
  }

  public static Connection startNetserverAndGetLocalNetConnection(
      Properties props) throws Exception {
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    // Use this VM as the network client
    TestUtil.loadNetDriver();
    startNetServer(netPort, null);
    return getNetConnection(netPort, null, props);
  }

  public static int startNetserverAndReturnPort() throws Exception {
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    // Use this VM as the network client
    TestUtil.loadNetDriver();
    startNetServer(netPort, null);
    return netPort;
  }

  public static int startNetserverAndReturnPort(String sql) throws Exception {
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    // Use this VM as the network client
    TestUtil.loadNetDriver();
    startNetServer(netPort, null);
    Connection conn = getNetConnection(netPort, null, null);
    if(sql != null) {
      Statement stmnt = conn.createStatement();
      stmnt.execute(sql);
    }
    return netPort;
    //return getNetConnection(netPort, null, null);
  }

  public static String startNetServer() throws Exception {
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    // Use this VM as the network client
    TestUtil.loadNetDriver();
    return startNetServer(netPort, null);
  }

  public static String startNetServer(int netPort, Properties extraProps)
      throws Exception {
    return startNetServer("localhost", netPort, extraProps);
  }

  public static String startNetServer(String hostName, int netPort,
      Properties extraProps) throws Exception {
    if (netServer != null) {
      stopNetServer();
    }
    getLogger().info(
        "Starting a gemfirexd network server on " + hostName + ":" + netPort);

    if (extraProps == null) {
      extraProps = new Properties();
    }
    extraProps.setProperty(com.pivotal.gemfirexd.Property.DRDA_PROP_LOGCONNECTIONS, "true");
    // extraProps.setProperty("gemfirexd.drda.debug", "true");
    netServer = getFabricService().startNetworkServer(hostName, netPort,
        extraProps);
    return hostName + ':' + netPort;
  }

  public static boolean stopNetServer() {
    if (netServer != null) {
      Logger logger = null;
      try {
        logger = getLogger();
        netServer.stop();
        logger.info(netServer.status() + " gemfirexd network server on localhost");
      } catch (Exception ex) {
        if (logger != null) {
          logger.error("Failed in gemfirexd network server shutdown", ex);
        }
        fail("Failed in gemfirexd network server shutdown: " + ex);
      }
      netServer = null;
      return true;
    }
    return false;
  }

  public static int startLocator(final String addr, final int netPort,
      Properties props) throws Exception {
    final FabricLocator locator = FabricServiceManager
        .getFabricLocatorInstance();
    if (props == null) {
      props = TestUtil.doCommonSetup(null);
    }

    final int locatorPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    locator.start(addr, locatorPort, props);
    if (netPort > 0) {
      startNetServer(netPort, null);
    }
    return locatorPort;
  }

  public static GemFireTransaction getGFT(EmbedConnection conn) {
    ContextManager cm = conn.getContextManager();
    GemFireTransactionContext tc = (GemFireTransactionContext)cm
        .getContext("UserTransaction");
    return tc.getTransaction();
  }

  public static GemFireContainer getIndexContainer(LocalRegion baseTableRegion,
      String indexName) {
    List<GemFireContainer> allIndexes = ((GfxdIndexManager)baseTableRegion
        .getIndexUpdater()).getAllIndexes();
    for (GemFireContainer gfc : allIndexes) {
      if (gfc.getName().equals(indexName)) {
        return gfc;
      }
    }
    return null;
  }

  /**
   * Test method. Creates an SQLInteger embedded in GemFire key. Doesn't support
   * composite keys.
   * 
   * @param arg
   *          an int
   * @return a com.pivotal.gemfirexd.internal.engine.store.RegionKey.
   */
  public static RegionKey getGemFireKey(int arg, Region<?, ?> tableRegion)
      throws StandardException {
    DataValueFactory df = Misc.getMemStore().getDatabase()
        .getDataValueFactory();
    final DataValueDescriptor dvd = df.getDataValue(arg);
    return GemFireXDUtils.convertIntoGemfireRegionKey(dvd,
        (GemFireContainer)tableRegion.getUserAttribute(), true);
  }

  public static String numstr(int i) {
    switch(i) {
      case 0: return "zero";
      case 1: return "one";
      case 2: return "two";
      case 3: return "three";
      case 4: return "four";
      case 5: return "five";
      case 6: return "six";
      case 7: return "seven";
      case 8: return "eight";
      case 9: return "nine";
      default:
        return numstr(i/10) + numstr(i%10);
    }
  }

  public static final String EmbeddedeXADsClassName = 
      "com.pivotal.gemfirexd.internal.jdbc.EmbeddedXADataSource";

  public static final String NetClientXADsClassName =
      "io.snappydata.jdbc.ClientXADataSource";

  public static Object getXADataSource(String xaDsClassName) {
    ClassLoader contextLoader = (ClassLoader)AccessController
        .doPrivileged(new java.security.PrivilegedAction() {

          @Override
          public Object run() {
            return Thread.currentThread().getContextClassLoader();
          }
        });

    try {
      Object ds = null;
      if (contextLoader != null) {
        try {
          ds = Class.forName(xaDsClassName, true, contextLoader).newInstance();
        } catch (Exception e) {
          // context loader may not be correctly hooked up
          // with parent, try without it.
        }
      }

      if (ds == null) {
        ds = Class.forName(xaDsClassName).newInstance();
      }

      return ds;
    } catch (Exception e) {
      fail(e.toString());
      return null;
    }
  }

  public static void clearStatementCache() throws SQLException {
    EmbedConnection conn = null;
    try {

      /* Because the same set of Tables and PrepStatements are used, with or without 
       * index was not making any difference as the plan will be compiled only once.
       * 
       * This is to invalidate the table schema in between. probably this is a bug.
       */
      LanguageConnectionContext lcc = (LanguageConnectionContext)ContextService
          .getContextOrNull(LanguageConnectionContext.CONTEXT_ID);

      if (lcc == null) {
        conn = GemFireXDUtils.getTSSConnection(true, true, false);
        conn.getTR().setupContextStack();
        lcc = (LanguageConnectionContext)ContextService
            .getContextOrNull(LanguageConnectionContext.CONTEXT_ID);
      }

      CacheManager cm = lcc.getLanguageConnectionFactory().getStatementCache();
      cm.cleanAll();
      cm.ageOut();
    } catch (Exception e) {
      fail("Exception occured in clearing cache ", e);
    }
    finally {
      if (conn != null) {
        conn.getTR().restoreContextStack();
        conn.close();
      }
    }
  }

  public static <T> Object getField(Class<T> clazz, T instance,
      String fieldName) throws NoSuchFieldException, SecurityException,
      IllegalArgumentException, IllegalAccessException {
    Field f = clazz.getDeclaredField(fieldName);
    f.setAccessible(true);
    return f.get(instance);
  }

  public static void assertTimerLibraryLoaded() {
    final OSType ostype = NativeCalls.getInstance().getOSType();
    if (ostype == OSType.LINUX) {
      assertTrue("Couldn't initialize jni native timer for " + ostype,
          NanoTimer.isJNINativeTimerEnabled());
      assertTrue("Couldn't initialize the native timer for " + ostype,
          NanoTimer.isNativeTimerEnabled());
    }
  }
}
