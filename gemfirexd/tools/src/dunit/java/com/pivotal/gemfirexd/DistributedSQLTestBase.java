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

import java.io.File;
import java.io.IOException;
import java.io.Serializable;
import java.net.Socket;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.*;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.TransformerException;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.CacheListener;
import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.cache.PartitionAttributesFactory;
import com.gemstone.gemfire.cache.Region;
import com.gemstone.gemfire.cache.RegionAttributes;
import com.gemstone.gemfire.cache.asyncqueue.AsyncEventQueue;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.wan.GatewayReceiver;
import com.gemstone.gemfire.cache.wan.GatewaySender;
import com.gemstone.gemfire.distributed.DistributedMember;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.CachePerfStats;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionAttributesImpl;
import com.gemstone.gemfire.internal.concurrent.ConcurrentHashSet;
import com.pivotal.gemfirexd.NetworkInterface.ConnectionListener;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheListener;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheLoader;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdCacheWriter;
import com.pivotal.gemfirexd.internal.engine.ddl.catalog.GfxdSystemProcedures;
import com.pivotal.gemfirexd.internal.engine.distributed.GfxdConnectionWrapper;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.QueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.distributed.utils.GemFireXDUtils;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextManager;
import com.pivotal.gemfirexd.internal.iapi.services.context.ContextService;
import com.pivotal.gemfirexd.internal.iapi.services.sanity.SanityManager;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedStatement;
import com.pivotal.gemfirexd.internal.impl.sql.GenericPreparedStatement;
import com.pivotal.gemfirexd.internal.impl.store.raw.data.GfxdJarResource;
import io.snappydata.test.dunit.*;
import io.snappydata.test.util.TestException;
import org.apache.derby.drda.NetworkServerControl;
import org.apache.derby.iapi.services.monitor.ModuleFactory;
import org.apache.derby.iapi.services.monitor.Monitor;
import org.apache.derby.iapi.services.stream.HeaderPrintWriter;
import org.apache.derby.shared.common.error.ShutdownException;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.apache.derbyTesting.junit.TestConfiguration;
import org.apache.thrift.TProcessor;
import org.apache.thrift.transport.TTransport;
import org.w3c.dom.Element;
import org.xml.sax.SAXException;

/**
 * Base class for running distributed SQL tests. It provides a generic approach
 * to testing distributed SQL as follows:
 * 
 * 1) Start some number of clients and servers to be used for the test
 * 
 * 2) Add SQLs to be executed on a client or server (latter is useful for DDL
 * testing without available DML distribution) in order. When on server the SQL
 * is executed only locally.
 * 
 * 3) Provide SQL execution with verification against golden text XML for
 * verification, or just check for successful execution with at least one row
 * when golden text is not provided. This also can be done on either clients or
 * servers as in 2) above. The steps 2) and 3) can be freely inter-mixed as
 * required.
 * 
 * 4) For DDL tests, optionally provide a RegionAttributes object to be used for
 * verification of the region attributes on clients or servers.
 * 
 * @author swale
 * @since 6.0
 */
@SuppressWarnings("serial")
public class DistributedSQLTestBase extends DistributedTestBase {

  public static final float DB2_SMALLEST_REAL = -3.402E+38f;

  public static final float DB2_LARGEST_REAL = +3.402E+38f;

  public static final float DB2_SMALLEST_POSITIVE_REAL = +1.175E-37f;

  public static final float DB2_LARGEST_NEGATIVE_REAL = -1.175E-37f;

  public static final double DB2_SMALLEST_DOUBLE = -1.79769E+308d;

  public static final double DB2_LARGEST_DOUBLE = +1.79769E+308d;

  public static final double DB2_SMALLEST_POSITIVE_DOUBLE = +2.225E-307d;

  public static final double DB2_LARGEST_NEGATIVE_DOUBLE = -2.225E-307d;

  private static String currentTestName = null;
  private static String currentClassName = null;
  
  protected static final boolean isTransactional = false;
  /*!(Boolean.getBoolean(SanityManager.TEST_MODE_NON_TX)
      || Boolean.parseBoolean(System.getenv(SanityManager.TEST_MODE_NON_TX)));*/

  protected static volatile int vmCount;

  protected static final ArrayList<String> expectedDerbyExceptions =
    new ArrayList<String>();

  transient protected final List<VM> clientVMs = new ArrayList<VM>();

  transient protected final List<VM> serverVMs = new ArrayList<VM>();

  transient protected Map<DistributedMember, VM> members =
    new HashMap<DistributedMember, VM>();

  transient private String locatorString = null;

  public static final char fileSeparator = System.getProperty("file.separator")
      .charAt(0);

  private static transient DistributedSQLTestBase testInstance = null;
  
  private volatile boolean configureDefaultOffHeap = false;

  /** interface to get existing VM or add a new one */
  private static interface GetVM {

    VM get(int index, int numCurrentVMs, Host host);

    boolean addVM();

    String actionName();
  }

  transient private static final GetVM addNewClientVM = new GetVM() {

    @Override
    public VM get(int index, int numCurrentVMs, Host host) {
      return host.getVM(Host.getHost(0).getVMCount() - index - numCurrentVMs);
    }

    @Override
    public boolean addVM() {
      return true;
    }

    @Override
    public String actionName() {
      return "Starting";
    }
  };

  transient private static final GetVM addNewServerVM = new GetVM() {

    @Override
    public VM get(int index, int numCurrentVMs, Host host) {
      return host.getVM(index + numCurrentVMs);
    }

    @Override
    public boolean addVM() {
      return true;
    }

    @Override
    public String actionName() {
      return "Starting";
    }
  };

  transient private final GetVM restartServerVM = new GetVM() {

    @Override
    public VM get(int index, int numCurrentVMs, Host host) {
      return serverVMs.get(index);
    }

    @Override
    public boolean addVM() {
      return false;
    }

    @Override
    public String actionName() {
      return "Restarting";
    }
  };

  protected static final class AsyncVM {

    private final AsyncInvocation async;

    private final VM vm;

    private AsyncVM(final AsyncInvocation async, final VM vm) {
      this.async = async;
      this.vm = vm;
    }

    public final AsyncInvocation getInvocation() {
      return this.async;
    }

    public final VM getVM() {
      return this.vm;
    }
  }

  /**
   * Creates a new <code>DistributedSQLTestBase</code> base object with the
   * given name.
   */
  public DistributedSQLTestBase(String name) {
    super(name);
  }

  protected String reduceLogging() {
    return null;
  }

  public static final class SetLoggingLevel extends SerializableRunnable {

    private final String level;

    public SetLoggingLevel(String level) {
      this.level = level;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      TestUtil.reduceLogLevel(this.level);
    }
  }

  public static void startCommonLocator(int locatorPort) {
    FabricLocator loc = FabricServiceManager.getFabricLocatorInstance();
    if (loc.status() != FabricService.State.RUNNING) {
      try {
        loc.start("localhost", locatorPort, null);
      } catch (SQLException e) {
        throw new TestException("Failed to start locator", e);
      }
    }
    assert (loc.status() == FabricService.State.RUNNING);
  }

  public void beforeClass() throws Exception {
    super.beforeClass();
    // Start a locator once for whole suite
    Host.getLocator().invoke(DistributedSQLTestBase.class,
        "startCommonLocator", getDUnitLocatorPort());
  }

  protected static String getDUnitLocatorString() {
    return "localhost[" + getDUnitLocatorPort() + ']';
  }

  public static void resetConnection() throws SQLException {
    Connection conn = TestUtil.jdbcConn;
    if (conn != null) {
      try {
        conn.rollback();
        conn.close();
      } catch (SQLException ignored) {
      }
      TestUtil.jdbcConn = null;
    }
  }

  protected void commonSetUp() throws Exception {
    GemFireXDUtils.IS_TEST_MODE = true;

    expectedDerbyExceptions.clear();
    currentTestName = getName();
    currentClassName = this.getClass().getName();
    testInstance = this;
    if (currentTestName != null) {
      invokeInEveryVM(DistributedSQLTestBase.class, "setTestName",
          new Object[] { currentTestName, currentClassName });
    }

    // Setup the tests to use the common locator started in beforeClass
    this.locatorString = getDUnitLocatorString();

    TestUtil.setRandomUserName();

    int numVMs = Host.getHost(0).getVMCount();
    setLogFile(this.getClass().getName(), this.getName(), numVMs);
    invokeInEveryVM(this.getClass(), "setLogFile", new Object[] {
        this.getClass().getName(), this.getName(), numVMs });
  }

  protected void baseSetUp() throws Exception {
    super.setUp();
    commonSetUp();
    // reduce logging if test so requests
    String logLevel;
    if ((logLevel = reduceLogging()) != null) {
      reduceLogLevelForTest(logLevel);
    }
    resetConnection();
    invokeInEveryVM(DistributedSQLTestBase.class, "resetConnection");
  }

  @Override
  public void setUp() throws Exception {
    baseSetUp();
    deleteAllOplogFiles();
  }

  public static void deleteAllOplogFiles() throws IOException {
    try {
      File currDir = new File(".");
      File[] files = currDir.listFiles();
      getGlobalLogger().info("current dir is: " + currDir.getCanonicalPath());

      if (files != null) {
        for (File f : files) {
          if (f.getAbsolutePath().contains("BACKUPGFXD-DEFAULT-DISKSTORE")) {
            getGlobalLogger().info("deleting file: " + f + " from dir: " + currDir);
            f.delete();
          }
          if (f.isDirectory()) {
            File newDir = new File(f.getCanonicalPath());
            File[] newFiles = newDir.listFiles();
            for (File nf : newFiles) {
              if (nf.getAbsolutePath().contains("BACKUPGFXD-DEFAULT-DISKSTORE")) {
                getGlobalLogger().info(
                    "deleting file: " + nf + " from dir: " + newDir);
                nf.delete();
              }
            }
          }
        }
        for (File f : files) {
          if (f.getAbsolutePath().contains("GFXD-DD-DISKSTORE")) {
            getGlobalLogger().info("deleting file: " + f + " from dir: " + currDir);
            f.delete();
          }
          if (f.isDirectory()) {
            File newDir = new File(f.getCanonicalPath());
            File[] newFiles = newDir.listFiles();
            for (File nf : newFiles) {
              if (nf.getAbsolutePath().contains("GFXD-DD-DISKSTORE")) {
                getGlobalLogger().info(
                    "deleting file: " + nf + " from dir: " + newDir);
                nf.delete();
              }
            }
          }
        }
      }
    } catch (IOException e) {
      // ignore ...
    }
  }

  protected void reduceLogLevelForTest(String logLevel) {
    SetLoggingLevel setLogLevel = new SetLoggingLevel(logLevel);
    invokeInEveryVM(setLogLevel);
    setLogLevel.run();
  }

  public static void setLogFile(final String className, final String name,
      final int numVMs) throws Exception {
    final Class<?> c = Class.forName(className);
    final DistributedSQLTestBase test = (DistributedSQLTestBase)c
        .getConstructor(String.class).newInstance(name);
    final String logFilePrefix = test.getTestLogPrefix();
    TestUtil.setPropertyIfAbsent(null, GfxdConstants.GFXD_LOG_FILE,
        logFilePrefix + ".log");
    // also set the client driver properties
    TestUtil.setPropertyIfAbsent(null, GfxdConstants.GFXD_CLIENT_LOG_FILE,
        logFilePrefix + "-client.log");
    GemFireXDUtils.initFlags();
    // set preallocate to false in all the dunit
    setPreallocateSysPropsToFalse();
    vmCount = numVMs;
  }

  public static void setPreallocateSysPropsToFalse() {
    System.setProperty("gemfire.preAllocateDisk", "false");
  }

  public static void setTestName(String name, String className) {
    try {
      currentTestName = name;
      currentClassName = className;
      final Class<?> c = Class.forName(className);
      testInstance = (DistributedSQLTestBase)c.getConstructor(String.class)
          .newInstance(name);
    } catch (Exception ex) {
      throw new TestException("unexpected exception", ex);
    }
  }

  public String getLocatorString() {
    return this.locatorString != null ? this.locatorString
        : (this.locatorString = getDUnitLocatorString());
  }

  public static String getClassName() {
    return currentClassName;
  }

  public static String getTestName() {
    return currentTestName;
  }

  public static String getCurrentDefaultSchemaName() {
    return TestUtil.getCurrentDefaultSchemaName();
  }

  /**
   * Helper method that causes this test to fail because of the given
   * exception.
   */
  public static void fail(String message, Throwable ex) {
    getGlobalLogger().error(message, ex);
    DistributedTestBase.fail(message, ex);
  }

  protected void configureDefaultOffHeap(boolean on) {
    this.configureDefaultOffHeap = on;
    if(this.configureDefaultOffHeap) {
      System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
      System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    }
  }

  protected static String getSysDirName() {
    return new File(".").getAbsolutePath();
  }

  protected void setGFXDProperty(Properties props, Object propName,
      Object propValue) {
    if (propName instanceof String && propValue instanceof String) {
      if (props.getProperty((String)propName) == null) {
        props.setProperty((String)propName, (String)propValue);
      }
    }
    else if (!props.containsKey(propName)) {
      props.put(propName, propValue);
    }
    /*
    if (props != null && !props.containsKey(propName)) {
      props.put(propName, propValue);
    }
    else {
      System.setProperty(DistributionConfig.GEMFIRE_PREFIX
          + propName.toString(), propValue.toString());
    }
    */
  }

  protected boolean containsGemFireProperty(Properties props, Object propName) {
    if (props != null) {
      String prop;
      if (propName.toString().startsWith(DistributionConfig.GEMFIRE_PREFIX)) {
        prop = propName.toString().substring(
            DistributionConfig.GEMFIRE_PREFIX.length());
      }
      else {
        prop = "";
      }
      return props.containsKey(propName)
          || (prop.length() > 0 && props.containsKey(prop));
    }
    else {
      return (System.getProperty(propName.toString()) != null);
    }
  }

  protected void setCommonProperties(Properties props, int mcastPort,
      String serverGroups, Properties extraProps) {
    assert props != null;
    // ---- system files (logs and archive) ----//
    final String sysDirName = getSysDirName();
    final String testLogPrefix = getTestLogPrefix();
    setGFXDProperty(props, DistributionConfig.LOG_FILE_NAME, testLogPrefix
        + ".log");
    String dirPath = null;
    if (extraProps != null) {
      dirPath = extraProps.getProperty(com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR);
    }
    if (dirPath != null && !dirPath.equals("")) {
      setGFXDProperty(props, com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, dirPath);
    }
    else {
      setGFXDProperty(props, com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR, sysDirName);
    }

    if (extraProps != null) {
      if(this.configureDefaultOffHeap) {
        if( !(extraProps.containsKey("gemfire.off-heap-memory-size")
            || System.getProperty("gemfire.off-heap-memory-size") != null) ) {
          extraProps.setProperty("gemfire.off-heap-memory-size", "500m");
        }
      }
      for (Map.Entry<Object, Object> entry : extraProps.entrySet()) {
        setGFXDProperty(props, entry.getKey(), entry.getValue());
      }
      
    }else {
      if(this.configureDefaultOffHeap) {
        if( !( System.getProperty("gemfire.off-heap-memory-size") != null) ) {
          setGFXDProperty(props, "gemfire.off-heap-memory-size", "500m");
          
        }
      }
      
    }

    // for DRDA tracing
    //System.setProperty(Property.DRDA_PROP_TRACEDIRECTORY, sysDirName);
    //System.setProperty(Property.DRDA_PROP_TRACEALL, "true");

    setGFXDProperty(props, DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
        testLogPrefix + ".gfs");
    //setGFXDProperty(props, DistributionConfig.STATISTIC_SAMPLING_ENABLED_NAME,
    //    "true");
    setGFXDProperty(props, DistributionConfig.BIND_ADDRESS_NAME, "localhost");

    // get the VM specific properties from DUnitEnv
    Properties dsProps = DUnitEnv.get().getDistributedSystemProperties();
    for (String prop : dsProps.stringPropertyNames()) {
      if (props.getProperty(prop) == null &&
          System.getProperty("gemfire." + prop) == null) {
        props.setProperty(prop, dsProps.getProperty(prop));
      }
    }

    //setGFXDProperty(props, "enable-network-partition-detection", "true");
    // reduce timeout properties for faster dunit runs
    // System.setProperty("p2p.discoveryTimeout", "2000");
    // System.setProperty("p2p.joinTimeout", "2000");
    System.setProperty("p2p.minJoinTries", "1");
    // System.setProperty("p2p.disconnectDelay", "1000");
    // System.setProperty("p2p.listenerCloseTimeout", "5000");

    if (extraProps != null) {
      Enumeration<?> e = extraProps.propertyNames();
      while (e.hasMoreElements()) {
        Object k = e.nextElement();
        if (k == null || !(k instanceof String)) {
          continue;
        }
        if (((String)k).contains("auth-ldap")) {
          String v = extraProps.getProperty((String)k);
          System.setProperty((String)k, v);
        }
      }
    }
    setGFXDProperty(props, "conserve-sockets", "true");
    setGFXDProperty(props, DistributionConfig.STATISTIC_SAMPLING_ENABLED_NAME,
        "true");
    System.setProperty("gemfirexd-impl.observer-volatile-read", "true");
    if (serverGroups != null) {
      props.setProperty("server-groups", serverGroups);
    }
    if (mcastPort > 0) {
      setGFXDProperty(props, "mcast-port", Integer.toString(mcastPort));
    }
    else if (!containsGemFireProperty(props, "gemfire.locators")
        && !props.containsKey("start-locator")
        && !props.containsKey("locators") && !props.containsKey("mcast-port")) {
      // Get the locator for the distributed system
      setGFXDProperty(props, "mcast-port", "0");
      setGFXDProperty(props, "locators", getLocatorString());
    }
    // use partitioned tables by default for tests
    setGFXDProperty(props,
        com.pivotal.gemfirexd.Attribute.TABLE_DEFAULT_PARTITIONED, "true");

    setOtherCommonProperties(props, mcastPort, serverGroups);
  }

  protected Properties setMasterCommonProperties(Properties props) {
    if (TestUtil.currentUserName != null) {
      if (props == null) {
        props = new Properties();
      }
      if (!props.containsKey(com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR)) {
        TestUtil.setPropertyIfAbsent(props, com.pivotal.gemfirexd.Attribute.USERNAME_ATTR,
            TestUtil.currentUserName);
      }
      TestUtil.setPropertyIfAbsent(props, com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR,
          TestUtil.currentUserPassword);
    }
    return props;
  }

  /**
   * Tests can override this to insert other properties or override some
   * existing ones.
   */
  protected void setOtherCommonProperties(Properties props, int mcastPort,
      String serverGroups) {
    System.setProperty("gemfire.DISALLOW_CLUSTER_RESTART_CHECK", "true");
  }

  public static DistributedMember _startNewLocator(String className,
      String name, String locatorBindAdress, final int locatorPort,
      final String extraLocatorArgs, Properties extraProps) throws Exception {
    final Class<?> c = Class.forName(className);
    final DistributedSQLTestBase test = (DistributedSQLTestBase)c
        .getConstructor(String.class).newInstance(name);
    if (extraProps != null && !extraProps.containsKey("locators")) {
      if (locatorBindAdress == null) {
        locatorBindAdress = "localhost";
      }
      extraProps.setProperty("locators", locatorBindAdress + '[' + locatorPort
          + ']');
    }
    Properties props = test.getLocatorConfig(extraProps);
    TestUtil.loadDriver();
    return TestUtil.setupConnection(locatorBindAdress, locatorPort,
        extraLocatorArgs, props);
  }

  public void startLocatorVM(final String locatorBindAdress,
      final int locatorPort, final String extraLocatorArgs,
      final Properties extraProps) throws Exception {
    final Host host = Host.getHost(0);
    final Properties vmExtraProps = new Properties();
    if (extraProps != null) {
      vmExtraProps.putAll(extraProps);
    }
    setMasterCommonProperties(vmExtraProps);
    joinVM(true, invokeStartLocatorVM(0, locatorBindAdress, locatorPort,
        extraLocatorArgs, host, vmExtraProps, addNewServerVM));
  }

  public void restartLocatorVM(final int vmNum, final String locatorBindAdress,
      final int locatorPort, final String extraLocatorArgs,
      final Properties extraProps) throws Exception {
    final Host host = Host.getHost(0);
    final Properties vmExtraProps = new Properties();
    if (extraProps != null) {
      vmExtraProps.putAll(extraProps);
    }
    setMasterCommonProperties(vmExtraProps);
    joinVM(false, invokeStartLocatorVM(vmNum - 1, locatorBindAdress,
        locatorPort, extraLocatorArgs, host, vmExtraProps, restartServerVM));
  }

  protected AsyncVM invokeStartLocatorVM(int index,
      final String locatorBindAdress, final int locatorPort,
      final String extraLocatorArgs, final Host host,
      final Properties vmExtraProps, final GetVM getVM) throws Exception {
    int currentServerVMs = this.serverVMs.size();
    if (getVM.addVM()) {
      int totalVMs = this.clientVMs.size() + currentServerVMs;
      final int maxVMs = Host.getHost(0).getVMCount();
      assertTrue("Total number of available VMs is " + maxVMs,
          totalVMs <= maxVMs);
    }
    final VM newVM = getVM.get(index, currentServerVMs, host);
    getLogWriter().info(
        getVM.actionName() + " VM with pid [" + newVM.getPid()
            + "] as locator.");
    return new AsyncVM(newVM.invokeAsync(this.getClass(), "_startNewLocator",
        new Object[] { getClass().getName(), getName(), locatorBindAdress,
            locatorPort, extraLocatorArgs, vmExtraProps }), newVM);
  }

  protected Properties getLocatorConfig(Properties extraProps) throws Exception {
    Properties props = new Properties();
    setCommonProperties(props, 0, null, extraProps);
    // Set the role indicating that this is a GemFireXD locator VM
    TestUtil.setPropertyIfAbsent(props, "host-data", "false");
    return props;
  }

  public static DistributedMember _startNewClient(String className,
      String name, int mcastPort, String serverGroups, Properties extraProps, 
      boolean configureOffHeap)
  
      throws Exception {
    final Class<?> c = Class.forName(className);
    DistributedSQLTestBase test = (DistributedSQLTestBase)c.getConstructor(
        String.class).newInstance(name);
    test.configureDefaultOffHeap = configureOffHeap;
    Properties props = test
        .getClientConfig(mcastPort, serverGroups, extraProps);
    TestUtil.loadDriver();
    return TestUtil.setupConnection(props);
  }

  public void startClientVMs(int numClients, final int mcastPort,
      final String serverGroups) throws Exception {
    startClientVMs(numClients, mcastPort, serverGroups, null, addNewClientVM);
  }

  public void startClientVMs(int numClients, final int mcastPort,
      final String serverGroups, final Properties extraProps) throws Exception {
    startClientVMs(numClients, mcastPort, serverGroups, extraProps,
        addNewClientVM);
  }

  private void startClientVMs(int numClients, final int mcastPort,
      final String serverGroups, final Properties extraProps, final GetVM getVM)
      throws Exception {
    final ArrayList<AsyncVM> startList = new ArrayList<AsyncVM>(5);
    invokeStartClientVMs(numClients, mcastPort, serverGroups, extraProps,
        getVM, startList);
    final boolean addVM = getVM.addVM();
    // wait for async start of other clients
    for (AsyncVM async : startList) {
      joinVM(false, addVM, async);
    }
  }

  protected void invokeStartClientVMs(int numClients, final int mcastPort,
      final String serverGroups, final Properties extraProps,
      final GetVM getVM, final List<AsyncVM> startList) throws Exception {
    assertTrue("At least one client should started", numClients > 0);
    int currentClientVms = this.clientVMs.size();
    final boolean addVM = getVM.addVM();
    if (addVM) {
      int totalVMs = numClients + currentClientVms + this.serverVMs.size();
      final int maxVMs = Host.getHost(0).getVMCount()+1;
      assertTrue("Total number of available VMs is " +maxVMs
          , totalVMs <= maxVMs);
    }
    
    String vmType;
    if (extraProps == null || (vmType = extraProps.getProperty(com.pivotal.gemfirexd.Attribute
        .GFXD_HOST_DATA)) == null || !Boolean.parseBoolean(vmType)) {
      vmType = "client.";
    }
    else {
      vmType = "server.";
    }
    final Host host = Host.getHost(0);
    final String action = getVM.actionName();
    Properties controllerProps = null;
    boolean hasController = false;
    if (currentClientVms == 0) {
      currentClientVms = 1;
      --numClients;
      hasController = true;
      if (extraProps != null && extraProps.size() > 0) {
        controllerProps = new Properties();
        controllerProps.putAll(extraProps);
      }
    }
    if (numClients > 0) {
      final Properties vmExtraProps = new Properties();
      if (extraProps != null && extraProps.size() > 0) {
        vmExtraProps.putAll(extraProps);
      }
      setMasterCommonProperties(vmExtraProps);
      for (int index = 0; index < numClients; ++index) {
        final VM newVM = getVM.get(index, currentClientVms, host);
        if (newVM != null) {
          getLogWriter().info(
              action + " VM with pid [" + newVM.getPid() + "] as " + vmType);
          // sync start for a single client
          if (numClients == 1 && !hasController) {
            this.members.put((DistributedMember)newVM.invoke(this.getClass(),
                "_startNewClient", new Object[] { getClass().getName(),
                    getName(), mcastPort, serverGroups, vmExtraProps , 
                    Boolean.valueOf(this.configureDefaultOffHeap)}), newVM);
            this.clientVMs.add(newVM);
          }
          else {
            startList.add(new AsyncVM(newVM.invokeAsync(this.getClass(),
                "_startNewClient", new Object[] { getClass().getName(),
                  getName(), mcastPort, serverGroups, vmExtraProps,
                  Boolean.valueOf(this.configureDefaultOffHeap) }), newVM));
          }
        }
        else {
          // newVM can be null for restart VM case
          hasController = true;
          if (extraProps != null && extraProps.size() > 0) {
            controllerProps = new Properties();
            controllerProps.putAll(extraProps);
          }
        }
      }
    }
    if (hasController) {
      getLogWriter().info(action + " this controller VM as " + vmType);
      this.members.put(
          _startNewClient(this.getClass().getName(), this.getName(), mcastPort,
              serverGroups, controllerProps, this.configureDefaultOffHeap), null);
      // Make the first client VM as null indicating use of the controller VM.
      if (addVM) {
        this.clientVMs.add(null);
      }
    }
  }

  public void restartClientVMNums(final int[] vmNums, int mcastPort,
      String serverGroups, Properties extraProps) throws Exception {
    final ArrayList<AsyncVM> restartList = new ArrayList<AsyncVM>(5);
    invokeRestartClientVMNums(vmNums, vmNums.length, mcastPort, serverGroups,
        extraProps, restartList);
    // wait for async start of other clients
    for (AsyncVM async : restartList) {
      joinVM(false, false, async);
    }
  }

  private void invokeRestartClientVMNums(final int[] vmNums, final int numVMs,
      int mcastPort, String serverGroups, Properties extraProps,
      final List<AsyncVM> restartList) throws Exception {
    invokeStartClientVMs(numVMs, mcastPort, serverGroups, extraProps,
        new GetVM() {

          @Override
          public VM get(int index, int numCurrentVMs, Host host) {
            return clientVMs.get(vmNums[index] - 1);
          }

          @Override
          public boolean addVM() {
            return false;
          }

          @Override
          public String actionName() {
            return "Restarting";
          }
        }, restartList);
  }

  protected Properties getClientConfig(int mcastPort, String serverGroups,
      Properties extraProps) throws Exception {
    Properties props = new Properties();
    setCommonProperties(props, mcastPort, serverGroups, extraProps);
    // Set the role indicating that this is a GemFireXD client VM
    TestUtil.setPropertyIfAbsent(props, "host-data", "false");
    return props;
  }

  public static DistributedMember _startNewServer(String className,
      String name, int mcastPort, String serverGroups, Properties extraProps,
      boolean configureOffHeap) throws Exception {
    final Class<?> c = Class.forName(className);

    final DistributedSQLTestBase test = (DistributedSQLTestBase)c
        .getConstructor(String.class).newInstance(name);
    test.configureDefaultOffHeap = configureOffHeap;
    Properties props = test
        .getServerConfig(mcastPort, serverGroups, extraProps);
    TestUtil.loadDriver();
    return TestUtil.setupConnection(props);
  }

  public void startServerVMs(int numServers, final int mcastPort,
      final String serverGroups) throws Exception {
    startServerVMs(numServers, mcastPort, serverGroups, null, addNewServerVM);
  }

  public void startServerVMs(int numServers, final int mcastPort,
      final String serverGroups, final Properties extraProps) throws Exception {
    startServerVMs(numServers, mcastPort, serverGroups, extraProps,
        addNewServerVM);
  }

  private void startServerVMs(int numServers, final int mcastPort,
      final String serverGroups, final Properties extraProps, final GetVM getVM)
      throws Exception {
    final ArrayList<AsyncVM> startList = new ArrayList<AsyncVM>(5);
    invokeStartServerVMs(numServers, mcastPort, serverGroups, extraProps,
        getVM, startList);
    // wait for async starts to complete
    final boolean addVM = getVM.addVM();
    for (AsyncVM async : startList) {
      joinVM(true, addVM, async);
    }
  }

  private void invokeStartServerVMs(int numServers, final int mcastPort,
      final String serverGroups, final Properties extraProps,
      final GetVM getVM, final List<AsyncVM> startList) throws Exception {
    assertTrue("Negative number of servers!", numServers >= 0);
    final int maxVms = Host.getHost(0).getVMCount();
    assertTrue("Maximum number of servers is "+ maxVms, numServers <= maxVms);

    final Host host = Host.getHost(0);
    final Properties vmExtraProps = new Properties();
    if (extraProps != null) {
      vmExtraProps.putAll(extraProps);
    }
    setMasterCommonProperties(vmExtraProps);
    for (int index = 0; index < numServers; ++index) {
      startList.add(invokeStartServerVM(index, mcastPort, serverGroups, host,
          vmExtraProps, getVM));
    }
  }

  /**
   * Start a new server VM beyond the current ones. Usually vmNum should start
   * with 1 to start the next VM available, and subsequent invokeStartServerVM
   * calls should be 2, 3 etc. as long as a joinVM with "addVM" as true has not
   * been invoked.
   */
  public AsyncVM invokeStartServerVM(final int vmNum, final int mcastPort,
      final String serverGroups, final Properties extraProps) throws Exception {
    final Host host = Host.getHost(0);
    final Properties vmExtraProps = new Properties();
    if (extraProps != null) {
      vmExtraProps.putAll(extraProps);
    }
    setMasterCommonProperties(vmExtraProps);
    return invokeStartServerVM(vmNum - 1, mcastPort, serverGroups, host,
        vmExtraProps, addNewServerVM);
  }

  private AsyncVM invokeStartServerVM(int index, final int mcastPort,
      final String serverGroups, final Host host,
      final Properties vmExtraProps, final GetVM getVM) throws Exception {
    int currentServerVMs = this.serverVMs.size();
    if (getVM.addVM()) {
      int totalVMs = this.clientVMs.size() + currentServerVMs + 1;
      final int maxVMs = Host.getHost(0).getVMCount()+1;
      assertTrue("Total number of available VMs is "+ maxVMs, totalVMs <= maxVMs);
    }

    final VM newVM = getVM.get(index, currentServerVMs, host);
    getLogWriter().info(getVM.actionName() + " VM with pid ["
        + newVM.getPid() + "] as server.");
    return new AsyncVM(newVM.invokeAsync(this.getClass(), "_startNewServer",
        new Object[] { getClass().getName(), getName(), mcastPort,
            serverGroups, vmExtraProps,Boolean.valueOf(this.configureDefaultOffHeap) }), newVM);
  }

  /**
   * This will start the given number of clients and servers asynchronously in
   * parallel with default properties.
   */
  public void startVMs(int numClients, int numServers) throws Exception {
    startVMs(numClients, numServers, 0, null, null);
  }

  /**
   * This will start the given number of clients and servers asynchronously in
   * parallel.
   */
  public void startVMs(int numClients, int numServers, int mcastPort,
      String serverGroups, Properties extraProps) throws Exception {
    final ArrayList<AsyncVM> serverStartList = new ArrayList<AsyncVM>(9);
    final ArrayList<AsyncVM> clientStartList = new ArrayList<AsyncVM>(9);
    if (numServers > 0) {
      invokeStartServerVMs(numServers, mcastPort, serverGroups, extraProps,
          addNewServerVM, serverStartList);
    }
    if (numClients > 0) {
      invokeStartClientVMs(numClients, mcastPort, serverGroups, extraProps,
          addNewClientVM, clientStartList);
    }
    // wait for async start of servers
    for (AsyncVM async : serverStartList) {
      joinVM(true, true, async);
    }
    // wait for async start of other clients
    for (AsyncVM async : clientStartList) {
      joinVM(false, true, async);
    }
  }

  public void restartServerVMNums(final int[] vmNums, int mcastPort,
      String serverGroups, Properties extraProps) throws Exception {
    final ArrayList<AsyncVM> restartList = new ArrayList<AsyncVM>(5);
    invokeRestartServerVMNums(vmNums, vmNums.length, mcastPort, serverGroups,
        extraProps, restartList);
    // wait for async starts to complete
    for (AsyncVM async : restartList) {
      joinVM(true, false, async);
    }
  }

  private void invokeRestartServerVMNums(final int[] vmNums, final int numVMs,
      int mcastPort, String serverGroups, Properties extraProps,
      final List<AsyncVM> restartList) throws Exception {
    assertTrue("Negative number of servers!", vmNums.length >= 0);
    final int maxVms = Host.getHost(0).getVMCount();
    assertTrue("Maximum number of servers is "+ maxVms, vmNums.length <= maxVms);

    final Host host = Host.getHost(0);
    final Properties vmExtraProps = new Properties();
    if (extraProps != null) {
      vmExtraProps.putAll(extraProps);
    }
    setMasterCommonProperties(vmExtraProps);
    for (int index = 0; index < numVMs; ++index) {
      restartList.add(invokeStartServerVM(vmNums[index] - 1, mcastPort,
          serverGroups, host, vmExtraProps, restartServerVM));
    }
  }

  public AsyncVM restartServerVMAsync(int vmNum, int mcastPort,
      String serverGroups, Properties extraProps) throws Exception {
    final Host host = Host.getHost(0);
    final Properties vmExtraProps = new Properties();
    if (extraProps != null) {
      vmExtraProps.putAll(extraProps);
    }
    setMasterCommonProperties(vmExtraProps);
    return invokeStartServerVM(vmNum - 1, mcastPort, serverGroups, host,
        vmExtraProps, restartServerVM);
  }

  /**
   * Restart VMs with given array of numbers with -ve indicating servers and +ve
   * indicating clients.
   */
  public void restartVMNums(final int... vmNums) throws Exception {
    restartVMNums(vmNums, 0, null, null);
  }

  /**
   * Restart VMs with given array of numbers with -ve indicating servers and +ve
   * indicating clients.
   */
  public void restartVMNums(final int[] vmNums, int mcastPort,
      String serverGroups, Properties extraProps) throws Exception {
    final ArrayList<AsyncVM> restartList = new ArrayList<AsyncVM>(5);

    final int[] serverVMNums = new int[vmNums.length];
    final int[] clientVMNums = new int[vmNums.length];
    int serverVMIndex = 0;
    int clientVMIndex = 0;
    for (int vmNum : vmNums) {
      if (vmNum < 0) { // server VM
        serverVMNums[serverVMIndex++] = -vmNum;
      }
      else { // client VM
        clientVMNums[clientVMIndex++] = vmNum;
      }
    }
    if (serverVMIndex > 0) {
      invokeRestartServerVMNums(serverVMNums, serverVMIndex, mcastPort,
          serverGroups, extraProps, restartList);
    }
    if (clientVMIndex > 0) {
      invokeRestartClientVMNums(clientVMNums, clientVMIndex, mcastPort,
          serverGroups, extraProps, restartList);
    }
    // wait for async start of servers and clients
    for (AsyncVM async : restartList) {
      joinVM(false, async);
    }
  }

  public void joinVM(boolean addVM, AsyncVM asyncVM) throws Exception {
    joinVM(true, addVM, asyncVM);
  }

  public void joinVMs(boolean addVM, AsyncVM... vmEntries) throws Exception {
    for (int index = 0; index < vmEntries.length; ++index) {
      joinVM(true, addVM, vmEntries[index]);
    }
  }

  protected void joinVM(boolean isServer, boolean addVM, AsyncVM asyncVM)
      throws Exception {
    final AsyncInvocation async = asyncVM.getInvocation();
    final VM newVM = asyncVM.getVM();
    joinAsyncInvocation(async, newVM);
    final Object result = async.getReturnValue();
    if (result != null && result instanceof DistributedMember) {
      if (addVM) {
        if (isServer) {
          this.serverVMs.add(newVM);
        }
        else {
          this.clientVMs.add(newVM);
        }
      }
      this.members.put((DistributedMember)result, newVM);
    }
  }

  protected Properties getServerConfig(int mcastPort, String serverGroups,
      Properties extraProps) throws Exception {
    Properties props = new Properties();
    setCommonProperties(props, mcastPort, serverGroups, extraProps);
    // Set the role indicating that this is a GemFireXD server VM
    TestUtil.setPropertyIfAbsent(props, "host-data", "true");
    return props;
  }

  private void shutDownRestart(int serverNum, long sleepTimeMilliSec)
      throws Exception {
    stopVMNums(-serverNum);
    assertTrue("sleep time before reconnect should be > 0",
        sleepTimeMilliSec > 0);
    try {
      Thread.sleep(sleepTimeMilliSec);
    } catch (InterruptedException e) {
      fail("exception while sleeping", e);
    }
    restartServerVMNums(new int[] { serverNum }, 0, null, null);
  }

  public void disconnectAndConnectAServerVM(final int serverNum,
      long sleepTimeMilliSec, boolean sync, List<AsyncInvocation> ainvoke)
      throws Exception {
    assertTrue("Negative server number!", serverNum >= 0);
    final int maxVms = Host.getHost(0).getVMCount();
    assertTrue("Maximum number of servers is "+maxVms , serverNum <= maxVms);
    if (sync) {
      restartServerVMNums(new int[] { serverNum }, 0, null, null);
    }
    else {
      final long sleepTime = sleepTimeMilliSec;
      final SerializableRunnable disconnConn =
          new SerializableRunnable("disconnConn") {
        @Override
        public void run() throws CacheException {
          try {
            shutDownRestart(serverNum, sleepTime);
          } catch (Exception e) {
            throw new CacheException("failed due to exception in restart", e) {
            };
          }
        }
      };
      final AsyncInvocation ai = new AsyncInvocation(disconnConn, "run",
          new Runnable() {
            public void run() {
              disconnConn.run();
            }
          });
      ai.start();
      ainvoke.add(ai);
    }
  }

  protected final AsyncVM stopVMNumAsync(int vmNum) {
    assertTrue("VM number should not be 0", vmNum != 0);
    final VM vm = getVM(vmNum);
    final String vmType = (vmNum < 0) ? "server" : "client";
    if (vm != null) {
      getLogWriter().info(
          "Stopping " + vmType + " VM with pid [" + vm.getPid() + "].");
      // Asif: Put the static testName & testClass in the TestUtil, rather
      // than keeping it here. This would help in deleting persistent files
      // from TestUtil itself, without refering to this Dunit. so junit can
      // also use it.
      return new AsyncVM(vm.invokeAsync(this.getClass(), "_stopVM",
          new Object[] { TestUtil.deletePersistentFiles }), vm);
    }
    else {
      return null;
    }
  }

  protected final void stopVM(final VM vm, final String vmType)
      throws SQLException {
    if (vm != null) {
      getLogWriter().info(
          "Stopping " + vmType + " VM with pid [" + vm.getPid() + "].");
      // Asif: Put the static testName & testClass in the TestUtil, rather
      // than keeping it here. This would help in deleting persistent files
      // from TestUtil itself, without refering to this Dunit. so junit can
      // also use it.
      vm.invoke(this.getClass(), "_stopVM",
          new Object[] { TestUtil.deletePersistentFiles });
    }
    else {
      getLogWriter().info("Stopping this controller VM as client.");
      _stopVM(TestUtil.deletePersistentFiles);
    }
  }

  /**
   * Stop the given client or server. Negative value indicates server (number as
   * -ve of the value passed) while positive indicates client number.
   */
  public final void stopVMNum(final int vmNum) throws SQLException {
    assertTrue("VM number should not be 0", vmNum != 0);
    if (vmNum < 0) {
      stopVM(getServerVM(-vmNum), "server");
    }
    else {
      stopVM(getClientVM(vmNum), "client");
    }
  }

  public static void _stopVM(final Boolean deletePersistenceFile)
      throws SQLException {
    TestUtil.shutDown();
    if (deletePersistenceFile.booleanValue()) {
      deleteTestArtifacts();
    }
  }

  /**
   * Stop given list of VM numbers. Negative indicates servers while positive
   * indicates clients.
   */
  public void stopVMNums(final int... vmNums) throws SQLException {
    assertTrue("At least one VM should be stopped", vmNums != null
        && vmNums.length > 0);
    for (int vmNum : vmNums) {
      stopVMNum(vmNum);
    }
    /*
    ArrayList<AsyncVM> stopList = new ArrayList<AsyncVM>(5);
    boolean stopController = false;
    for (int vmNum : vmNums) {
      final AsyncVM async = stopVMNumAsync(vmNum);
      if (async != null) {
        stopList.add(async);
      }
      else {
        stopController = true;
      }
    }
    if (stopController) {
      getLogWriter().info("Stopping this controller VM as client.");
      _stopVM(TestUtil.deletePersistentFiles);
    }
    // now wait for async stops to end
    for (AsyncVM async : stopList) {
      try {
        joinAsyncInvocation(async.getInvocation(), async.getVM());
      } catch (InterruptedException ie) {
        throw new TestException(
            "unexpected interrupted exception in stopping VM " + async.getVM(),
            ie);
      }
    }
    */
  }

  public void stopAllVMs() throws SQLException {
    stopAllVMs(0);
  }

  /**
   * Stop all VMs synchronously. If lastVMNum is non-zero then that VM is
   * stopped separately at the end synchronously to ensure that it will have the
   * latest persisted DataDictionary or other data (-ve indicates server while
   * +ve indicates client).
   */
  public void stopAllVMs(final int lastVMNum) throws SQLException {
    for (int serverNum = 1; serverNum <= this.serverVMs.size(); serverNum++) {
      // if a lastVM has been specified skip it at this point
      if (lastVMNum != (-serverNum)) {
        stopVMNum(-serverNum);
      }
    }
    for (int clientNum = 1; clientNum <= this.clientVMs.size(); clientNum++) {
      // if a lastVM has been specified skip it at this point
      if (lastVMNum != clientNum) {
        stopVMNum(clientNum);
      }
    }
    if (lastVMNum != 0) {
      stopVMNum(lastVMNum);
    }
    else {
      getLogWriter().info("Stopping this controller VM as client.");
      _stopVM(TestUtil.deletePersistentFiles);
    }
  }

  /**
   * Stop all VMs asynchronously. If lastVMNum is non-zero then that VM is
   * stopped separately at the end synchronously to ensure that it will have the
   * latest persisted DataDictionary or other data (-ve indicates server while
   * +ve indicates client).
   * 
   * TODO: merge: below is causing jgroups thread leaks after recent merge;
   * still using for GFXD auth tests since those hang when using the sync
   * version
   */
  public void stopAllVMsAsync(final int lastVMNum) throws SQLException {
    // we stop all in parallel to be a bit quicker
    ArrayList<AsyncVM> stopList = new ArrayList<AsyncVM>(5);
    boolean stopController = false;
    for (int serverNum = 1; serverNum <= this.serverVMs.size(); ++serverNum) {
      // if a lastVM has been specified skip it at this point
      if (lastVMNum != (-serverNum)) {
        stopList.add(stopVMNumAsync(-serverNum));
      }
    }
    for (int clientNum = 1; clientNum <= this.clientVMs.size(); ++clientNum) {
      if (lastVMNum != clientNum) {
        final AsyncVM async = stopVMNumAsync(clientNum);
        if (async != null) {
          stopList.add(async);
        }
        else {
          stopController = true;
        }
      }
    }
    if (stopController) {
      getLogWriter().info("Stopping this controller VM as client.");
      _stopVM(TestUtil.deletePersistentFiles);
    }
    // now wait for async stops to end
    for (AsyncVM async : stopList) {
      try {
        joinAsyncInvocation(async.getInvocation(), async.getVM());
      } catch (InterruptedException ie) {
        throw new TestException(
            "unexpected interrupted exception in stopping VM " + async.getVM(),
            ie);
      }
    }
    // stop any specified VM at the end
    if (lastVMNum != 0) {
      stopVMNum(lastVMNum);
    }
  }

  public void clientSQLExecute(int clientNum, String jdbcSQL) throws Exception {
    // Default is to use PreparedStatement and not use typeInfo
    clientSQLExecute(clientNum, jdbcSQL, true, false, false);
  }

  public void clientSQLExecute(int clientNum, String jdbcSQL,
      boolean usePrepStmt, boolean checkTypeInfo, boolean consumeResults)
      throws Exception {
    assertTrue("Client number should be positive and not exceed total "
        + "number of clients: " + this.clientVMs.size(), clientNum > 0
        && clientNum <= this.clientVMs.size());
    execute(this.clientVMs.get(clientNum - 1), jdbcSQL, false, null, null,
        null, null, null, usePrepStmt, checkTypeInfo, consumeResults);
  }

  public final VM getServerVM(int serverNum) {
    assertTrue("Server number should be positive and not exceed total "
        + "number of servers: " + this.serverVMs.size(), serverNum > 0
        && serverNum <= this.serverVMs.size());
    return this.serverVMs.get(serverNum - 1);
  }

  public final VM getClientVM(int vmNum) {
    assertTrue("Client number " + vmNum + " should not exceed total "
        + "number of clients: " + this.clientVMs.size(),
        vmNum <= this.clientVMs.size());
    return this.clientVMs.get(vmNum - 1);
  }

  public void serverExecute(int serverNum, Runnable runnable) throws Exception {
    getServerVM(serverNum).invoke(runnable);
  }

  public void clientExecute(int clientNum, Runnable runnable) throws Exception {
    assertTrue("Client number should be positive and not exceed total "
        + "number of clients: " + this.clientVMs.size(), clientNum > 0
        && clientNum <= this.clientVMs.size());
    VM vm = this.clientVMs.get(clientNum - 1);
    if (vm != null) {
      vm.invoke(runnable);
    }
    else {
      runnable.run();
    }
  }

  public Object serverExecute(int serverNum, Callable<?> callable)
      throws Exception {
    return getServerVM(serverNum).invoke(callable);
  }

  public Object clientExecute(int clientNum, Callable<?> callable)
      throws Exception {
    assertTrue("Client number should be positive and not exceed total "
        + "number of clients: " + this.clientVMs.size(), clientNum > 0
        && clientNum <= this.clientVMs.size());
    VM vm = this.clientVMs.get(clientNum - 1);
    if (vm != null) {
      return vm.invoke(callable);
    }
    else {
      return callable.call();
    }
  }

  public void serverSQLExecute(int serverNum, String jdbcSQL) throws Exception {
    // Default is to use PreparedStatement and not use typeInfo
    serverSQLExecute(serverNum, jdbcSQL, true, false, false);
  }

  public void serverSQLExecute(int serverNum, String jdbcSQL,
      boolean usePrepStmt, boolean checkTypeInfo, boolean consumeResults)
      throws Exception {
    assertTrue("Server number should be positive and not exceed total "
        + "number of servers: " + this.serverVMs.size(), serverNum > 0
        && serverNum <= this.serverVMs.size());
    execute(this.serverVMs.get(serverNum - 1), jdbcSQL, false, null, null,
        null, null, null, usePrepStmt, checkTypeInfo, consumeResults);
  }

  /**
   * Execute the given SQL string verifying the obtained {@link ResultSet}
   * against the provided XML string on the given client and server VMs. The XML
   * string can be either provided as name of the file (with "id" attribute --
   * see below in <code>resultSetID</code>) or can be provided inline. The
   * optional <code>usePrepStmt</code> specifies whether to use
   * {@link PreparedStatement} or to use {@link Statement} for SQL execution.
   * The verification can be either ordered (i.e. the order in results should
   * match exactly as given in the XML file) or unordered. The default is to use
   * unordered comparison which can be overriden by setting the "ordered"
   * attribute in the "resultSet" element in the XML to either "true" or
   * "false". For scalar results just give the goldenTextFile as null and
   * resultSetID as the scalar result.
   * 
   * @param clientNums
   *          the client numbers on which to do the verification
   * @param serverNums
   *          the server numbers on which to do the verification
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
   */
  public void sqlExecuteVerify(int[] clientNums, int[] serverNums,
      String jdbcSQL, String goldenTextFile, String resultSetID)
      throws Exception {
    // Default is to use PreparedStatement and not check for typeInfo
    sqlExecuteVerify(clientNums, serverNums, jdbcSQL, goldenTextFile,
        resultSetID, true, false);
  }

  /**
   * Execute the given SQL string verifying the obtained {@link ResultSet}
   * against the provided XML string on the given client and server VMs. The XML
   * string can be either provided as name of the file (with "id" attribute --
   * see below in <code>resultSetID</code>) or can be provided inline. The
   * optional <code>usePrepStmt</code> specifies whether to use
   * {@link PreparedStatement} or to use {@link Statement} for SQL execution.
   * The verification can be either ordered (i.e. the order in results should
   * match exactly as given in the XML file) or unordered. The default is to use
   * unordered comparison which can be overriden by setting the "ordered"
   * attribute in the "resultSet" element in the XML to either "true" or
   * "false". For scalar results just give the goldenTextFile as null and
   * resultSetID as the scalar result.
   * 
   * @param clientNums
   *          the client numbers on which to do the verification
   * @param serverNums
   *          the server numbers on which to do the verification
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
  public void sqlExecuteVerify(int[] clientNums, int[] serverNums,
      String jdbcSQL, String goldenTextFile, String resultSetID,
      boolean usePrepStmt, boolean checkTypeInfo) throws Exception {
    execute(getVMs(clientNums, serverNums), jdbcSQL, true, goldenTextFile,
        resultSetID, null, null, null, usePrepStmt, checkTypeInfo);
  }

  public void clientVerifyRegionProperties(int clientNum, String schemaName,
      String tableName, RegionAttributes<?, ?> expectedAttrs) throws Exception {
    assertTrue("Client number should be positive and not exceed total "
        + "number of clients: " + this.clientVMs.size(), clientNum > 0
        && clientNum <= this.clientVMs.size());
    execute(this.clientVMs.get(clientNum - 1), null, false, null, null,
        schemaName, tableName, expectedAttrs, false, false, false);
  }

  public void serverVerifyRegionProperties(int serverNum, String schemaName,
      String tableName, RegionAttributes<?, ?> expectedAttrs) throws Exception {
    assertTrue("Server number should be positive and not exceed total "
        + "number of servers: " + this.serverVMs.size(), serverNum > 0
        && serverNum <= this.serverVMs.size());
    execute(this.serverVMs.get(serverNum - 1), null, false, null, null,
        schemaName, tableName, expectedAttrs, false, false, false);
  }

  /**
   * Add a single expected exception string to the gemfirexd logs in the given
   * client and server VM numbers. The <code>exceptionClass</code> can be either
   * the {@link Class} object or fully qualified class name.
   */
  public void addExpectedException(final int[] clientNums,
      final int[] serverNums, final Object exceptionClass) {
    addExpectedException(clientNums, serverNums,
        new Object[] { exceptionClass });
  }

  /**
   * Add expected exceptions string to the gemfirexd logs in the given client
   * and server VM numbers. The <code>exceptionClasses</code> can be either the
   * {@link Class} object or fully qualified class name.
   */
  public void addExpectedException(final int[] clientNums,
      final int[] serverNums, final Object[] exceptionClasses) {
    for (VM vm : getVMs(clientNums, serverNums, true)) {
      addExpectedException(vm, exceptionClasses);
    }
  }

  /**
   * Add expected exceptions string to the gemfirexd logs in the given VM. The
   * <code>exceptionClasses</code> can be either the {@link Class} object or
   * fully qualified class name.
   */
  public void addExpectedException(VM vm, final Object[] exceptionClasses) {
    if (vm != null) {
      vm.invoke(TestUtil.class, "addExpectedExceptions",
          new Object[] { exceptionClasses });
    }
    else {
      TestUtil.addExpectedExceptions(exceptionClasses);
    }
  }

  /**
   * Add a single expected exception string with <code>remove</code> tag to the
   * gemfirexd logs in the given client and server VM numbers. The
   * <code>exceptionClass</code> can be either the {@link Class} object or
   * fully qualified class name.
   */
  public void removeExpectedException(final int[] clientNums,
      final int[] serverNums, final Object exceptionClass) {
    removeExpectedException(clientNums, serverNums,
        new Object[] { exceptionClass });
  }

  /**
   * Add expected exceptions string with <code>remove</code> tag to the
   * gemfirexd logs in the given client and server VM numbers. The
   * <code>exceptionClasses</code> can be either the {@link Class} object or
   * fully qualified class name.
   */
  public void removeExpectedException(final int[] clientNums,
      final int[] serverNums, final Object[] exceptionClasses) {
    for (VM vm : getVMs(clientNums, serverNums, true)) {
      removeExpectedException(vm, exceptionClasses);
    }
  }

  /**
   * Add expected exceptions string with <code>remove</code> tag to the
   * gemfirexd logs in the given VM. The <code>exceptionClasses</code> can be
   * either the {@link Class} object or fully qualified class name.
   */
  public void removeExpectedException(VM vm, final Object[] exceptionClasses) {
    if (vm != null) {
      vm.invoke(TestUtil.class, "removeExpectedExceptions",
          new Object[] { exceptionClasses });
    }
    else {
      TestUtil.removeExpectedExceptions(exceptionClasses);
    }
  }

  public ArrayList<AsyncInvocation> executeTaskAsync(int[] clientNums,
      int[] serverNums, Runnable r) {
    final ArrayList<VM> vms = getVMs(clientNums, serverNums);
    final ArrayList<AsyncInvocation> asyncList = new ArrayList<AsyncInvocation>(
        vms.size());
    for (VM vm : vms) {
      asyncList.add(vm.invokeAsync(r));
    }
    return asyncList;
  }

  public void joinAsyncInvocation(AsyncInvocation invocation, VM vm)
      throws InterruptedException {
    invocation.join();
    if (invocation.exceptionOccurred()) {
      final Object o = invocation.getReceiver();
      throw new RMIException(vm,
          (o instanceof Class<?>) ? ((Class<?>)o).getName() : o.getClass()
              .getName(), invocation.getMethodName(), invocation.getException());
    }
  }

  public void joinAsyncInvocation(Collection<AsyncInvocation> invocations)
      throws InterruptedException {
    for (AsyncInvocation invocation : invocations) {
      invocation.join();
      if (invocation.exceptionOccurred()) {
        fail("Error in [" + invocation.getReceiver() + "]", invocation
            .getException());
      }
    }
  }

  protected void addLogString(final int[] clientNums, final int[] serverNums,
      final String logStr) {
    for (VM vm : getVMs(clientNums, serverNums, true)) {
      if (vm != null) {
        vm.invoke(new SerializableRunnable("add log string") {
          @Override
          public void run() {
            TestUtil.addLogString(logStr);
          }
        });
      }
      else {
        TestUtil.addLogString(logStr);
      }
    }
  }

  public static void _startNetworkServer(String className, String name,
      int mcastPort, int netPort, String serverGroups, Properties extraProps,
      Boolean configureDefautHeap) throws Exception {
    final Class<?> c = Class.forName(className);

    // start a DataNode first.
    if (TestUtil.getFabricService().status() != FabricService.State.RUNNING) {
      _startNewServer(className, name, mcastPort, serverGroups, extraProps,
          configureDefautHeap);
    }

    DistributedSQLTestBase test = (DistributedSQLTestBase)c.getConstructor(
        String.class).newInstance(name);
    test.configureDefaultOffHeap = configureDefautHeap;
    
    final Properties props = new Properties();
    test.setCommonProperties(props, mcastPort, serverGroups, extraProps);
    TestUtil.startNetServer(netPort, props);
  }

  public static void shutDownNetworkServer() {
    TestUtil.stopNetServer();
  }

  /**
   * Start a network server on the locator.
   */
  public int startNetworkServerOnLocator(String serverGroups,
      Properties extraProps) throws Exception {
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    if (netPort <= 1024) {
      throw new AssertionError("unexpected random port " + netPort);
    }
    startNetworkServerOnLocator(serverGroups, extraProps, netPort);
    return netPort;
  }

  /**
   * Start a network server on the locator.
   */
  public void startNetworkServerOnLocator(String serverGroups,
      Properties extraProps, int netPort) throws Exception {
    final VM locatorVM = Host.getLocator();
    getLogWriter().info("Starting a network server on port=" + netPort +
        " on locator with pid [" + locatorVM.getPid() + ']');
    // Start a network server
    locatorVM.invoke(DistributedSQLTestBase.class, "_startNetworkServer",
        new Object[]{this.getClass().getName(), this.getName(), 0, netPort,
            serverGroups, extraProps, Boolean.valueOf(this.configureDefaultOffHeap)});
  }

  /**
   * Start a network server on given VM number (1-based) started with
   * {@link #startServerVMs} and return the TCP port being used by the
   * server that is chosen randomly based on availability.
   */
  public int startNetworkServer(int vmNum, String serverGroups,
      Properties extraProps) throws Exception {
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    if (netPort <= 1024) {
      throw new AssertionError("unexpected random port " + netPort);
    }
    startNetworkServer(vmNum, serverGroups, extraProps, netPort);
    return netPort;
  }

  /**
   * Start a network server on given VM number (1-based) started with
   * {@link #startServerVMs} given the TCP port to be used.
   */
  protected void startNetworkServer(int vmNum, String serverGroups,
      Properties extraProps, int netPort) throws Exception {
    assertTrue("VM number should be >= 1", vmNum >= 1);
    final int maxVMs = Host.getHost(0).getVMCount();
    assertTrue("Maximum number of servers is "+ maxVMs, vmNum <= maxVMs);
    final int totalVMs = this.clientVMs.size() + vmNum;
    assertTrue("Total number of available VMs is "+ maxVMs+1, totalVMs <= maxVMs+1);

    final Host host = Host.getHost(0);
    extraProps = setMasterCommonProperties(extraProps);
    final VM newVM = host.getVM(vmNum - 1);
    getLogWriter().info(
        "Starting VM with pid [" + newVM.getPid()
            + "] as gemfirexd network server on port: " + netPort);
    // Start a network server
    newVM.invoke(DistributedSQLTestBase.class, "_startNetworkServer",
        new Object[] { this.getClass().getName(), this.getName(), 0, netPort,
            serverGroups, extraProps, Boolean.valueOf(this.configureDefaultOffHeap) });
    if (vmNum > this.serverVMs.size()) {
      this.serverVMs.add(newVM);
    }
  }

  /**
   * Intermediate serializable adapter for {@link ConnectionListener}.
   */
  protected static abstract class SerializableConnectionListener implements
      ConnectionListener, Serializable {

    @Override
    public void close() {
      // nothing by default
    }
  }

  protected final static AtomicInteger numConnectionsOpened =
    new AtomicInteger(0);

  protected final static AtomicInteger numConnectionsClosed =
    new AtomicInteger(0);

  // ConnectionListeners for the servers
  protected static final SerializableConnectionListener connListener =
    new SerializableConnectionListener() {

    @Override
    public void connectionOpened(Socket clientSocket, int connectionNumber) {
      numConnectionsOpened.incrementAndGet();
    }

    @Override
    public void connectionOpened(TTransport clientSocket, TProcessor processor,
        int connectionNumber) {
      numConnectionsOpened.incrementAndGet();
    }

    @Override
    public void connectionClosed(Socket clientSocket, int connectionNumber) {
      numConnectionsClosed.incrementAndGet();
    }

    @Override
    public void connectionClosed(TTransport clientSocket, TProcessor processor,
        int connectionNumber) {
      numConnectionsClosed.incrementAndGet();
    }
  };

  protected final void attachConnectionListener(final VM vm,
      final SerializableConnectionListener listener) throws Exception {
    vm.invoke(this.getClass(), "attachConnectionListener",
        new Object[] { listener });
  }

  protected final void attachConnectionListener(int serverNum,
      final SerializableConnectionListener listener) throws Exception {
    attachConnectionListener(getServerVM(serverNum), listener);
  }

  public static final void attachConnectionListener(
      final SerializableConnectionListener listener) throws Exception {
    numConnectionsOpened.set(0);
    numConnectionsClosed.set(0);
    TestUtil.netServer.setConnectionListener(listener);
  }

  public static int[] getNumConnections() {
    return new int[] { numConnectionsOpened.get(), numConnectionsClosed.get() };
  }

  protected void assertNumConnections(final int expectedConnectionsOpened,
      final int expectedConnectionsClosed, final VM... vms) throws Exception {
    final WaitCriterion wc = new WaitCriterion() {
      private int openConns, closedConns;

      public boolean done() {
        boolean done;
        openConns = 0;
        closedConns = 0;
        for (VM vm : vms) {
          final int[] res = (int[])vm.invoke(DistributedSQLTestBase.class,
              "getNumConnections");
          openConns += res[0];
          closedConns += res[1];
        }
        // negative value indicates "at most"
        if (expectedConnectionsOpened < 0) {
          done = (-expectedConnectionsOpened) >= openConns;
        }
        else {
          done = (expectedConnectionsOpened == openConns);
        }
        // negative value indicates "at most"
        if (expectedConnectionsClosed < 0) {
          done &= (-expectedConnectionsClosed) >= closedConns;
        }
        else {
          done &= (expectedConnectionsClosed == closedConns);
        }
        return done;
      }

      public String description() {
        return "waiting for expectedConnectionsOpened="
            + expectedConnectionsOpened + ", expectedConnectionsClosed="
            + expectedConnectionsClosed + ", but got " + openConns + " and "
            + closedConns + " respectively";
      }
    };
    waitForCriterion(wc, 10000, 500, true);
  }

  protected void assertNumConnections(int expectedConnectionsOpened,
      int expectedConnectionsClosed, int... serverNums) throws Exception {
    final VM[] serverVMs = new VM[serverNums.length];
    assertNumConnections(expectedConnectionsOpened, expectedConnectionsClosed,
        getVMs(null, serverNums).toArray(serverVMs));
  }

  public boolean stopNetworkServerOnLocator() throws Exception {
    final VM locatorVM = Host.getLocator();
    getLogWriter().info("Stopping gemfirexd network server on locator with pid [" +
        locatorVM.getPid() + ']');
    return locatorVM.invokeBoolean(TestUtil.class, "stopNetServer");
  }

  public boolean stopNetworkServer(int vmNum) throws Exception {
    int currentServerVms = this.serverVMs.size();
    assertTrue("Server number [" + vmNum + "] should be >= 1", vmNum >= 1);
    assertTrue("Total number of server VMs is " + currentServerVms,
        vmNum <= currentServerVms);
    final VM vm = this.serverVMs.get(vmNum - 1);
    getLogWriter().info("Stopping VM with pid [" + vm.getPid()
        + "] as gemfirexd network server.");
    return vm.invokeBoolean(TestUtil.class, "stopNetServer");
  }

  static final class DoCleanup extends SerializableCallable {

    @Override
    public Object call() {
      try {
        final GemFireStore store;
        if ((store = GemFireStore.getBootedInstance()) != null
            && Misc.getGemFireCacheNoThrow() != null
            && GemFireXDUtils.getMyVMKind().isAccessorOrStore()) {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          // finally any installed JARs
          GfxdJarResource fr = (GfxdJarResource)store.getJarFileHandler();
          if (fr != null) {
            for (String jarName : fr.getNameToIDMap().keySet()) {
              executeCleanup(stmt, "call sqlj.remove_jar('" + jarName
                  + "', 0)");
            }
          }
          CleanDatabaseTestSetup.cleanDatabase(conn, false);
          return Boolean.TRUE;
        }
      } catch (Exception ex) {
        getGlobalLogger().error("unexpected exception in cleanup", ex);
      }
      return Boolean.FALSE;
    }
  }

  static final class DoPreCleanup extends SerializableCallable {

    @Override
    public Object call() {
      final boolean[] requiresCleanup = new boolean[3];

      // cleanup the observers before doing anything else, otherwise the
      // observers in tests can hang/fail the cleanup itself
      GemFireXDQueryObserverHolder.clearInstance();

      // cleanup any open transactions
      ContextService cs;
      try {
        cs = ContextService.getFactory();
      } catch (Exception e) {
        cs = null;
      }
      if (cs != null) {
        synchronized (cs) {
          ConcurrentHashSet<ContextManager> contexts = cs.getAllContexts();
          if (contexts != null) {
            for (ContextManager cm : contexts) {
              LanguageConnectionContext lcc;
              GemFireTransaction tran;
              try {
                lcc = (LanguageConnectionContext)cm
                    .getContext(LanguageConnectionContext.CONTEXT_ID);
              } catch (Exception e) {
                lcc = null;
              }
              if (lcc != null && (tran = (GemFireTransaction)lcc
                  .getTransactionExecute()) != null) {
                try {
                  tran.destroy();
                } catch (Exception e) {
                  // ignore exceptions
                }
                try {
                  tran.releaseAllLocks(true, true);
                } catch (Exception e) {
                  // ignore exceptions
                }
              }
            }
          }
        }
      }

      // possible IllegalStateExceptions later due to out of order colocated
      // tables drop
      TestUtil.addExpectedException(IllegalStateException.class.getName());

      // check if phase1/phase2/phase3 cleanups are required
      final GemFireCacheImpl cache;
      final GemFireStore store;
      if ((store = GemFireStore.getBootedInstance()) != null
          && (cache = Misc.getGemFireCacheNoThrow()) != null
          && !cache.isClosed()
          && GemFireXDUtils.getMyVMKind().isAccessorOrStore()) {
        // also GemFireXD specific objects
        for (GemFireContainer c : store.getAllContainers()) {
          if (!c.isApplicationTable()) {
            continue;
          }
          final LocalRegion r = c.getRegion();
          if (r == null) {
            continue;
          }
          if (r.getCacheLoader() instanceof GfxdCacheLoader) {
            requiresCleanup[0] = true;
            break;
          }
          for (CacheListener<?, ?> l : r.getCacheListeners()) {
            if (l instanceof GfxdCacheListener) {
              requiresCleanup[0] = true;
              break;
            }
            if (requiresCleanup[0]) {
              break;
            }
          }
          if (r.getCacheWriter() instanceof GfxdCacheWriter) {
            requiresCleanup[0] = true;
            break;
          }
        }

        requiresCleanup[1] = (cache.getAllGatewaySenders().size() > 0);

        if (cache.getGatewayReceivers().size() > 0) {
          requiresCleanup[2] = true;
        }
        else {
          for (DiskStoreImpl ds : cache.listDiskStores()) {
            if (!GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME.equals(ds.getName())
                && !GfxdConstants.GFXD_DD_DISKSTORE_NAME.equals(ds.getName())
                && !GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE.equals(ds.getName())) {
              requiresCleanup[2] = true;
              break;
            }
          }
          if (!requiresCleanup[2]) {
            if (GfxdSystemProcedures.GET_EVICTION_HEAP_PERCENTAGE() != 0.0f) {
              requiresCleanup[2] = true;
            }
            if (GfxdSystemProcedures.GET_CRITICAL_HEAP_PERCENTAGE() != 0.0f) {
              requiresCleanup[2] = true;
            }
          }
        }
      }

      return requiresCleanup;
    }
  }

  static final class DoCleanupOnAll1 extends SerializableRunnable {

    @Override
    public void run() {
      try {
        final GemFireStore store;
        if ((store = GemFireStore.getBootedInstance()) != null
            && Misc.getGemFireCacheNoThrow() != null
            && GemFireXDUtils.getMyVMKind().isAccessorOrStore()) {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          // also GemFireXD specific objects
          for (GemFireContainer c : store.getAllContainers()) {
            if (!c.isApplicationTable()) {
              continue;
            }
            final LocalRegion r = c.getRegion();
            if (r == null) {
              continue;
            }
            if (r.getCacheLoader() instanceof GfxdCacheLoader) {
              executeCleanup(stmt, "call sys.remove_loader('"
                  + c.getSchemaName() + "', '" + c.getTableName() + "')");
            }
            for (CacheListener<?, ?> l : r.getCacheListeners()) {
              if (l instanceof GfxdCacheListener) {
                executeCleanup(stmt, "call sys.remove_listener('"
                    + ((GfxdCacheListener)l).getName() + "', '"
                    + c.getSchemaName() + "', '" + c.getTableName() + "')");
              }
            }
            if (r.getCacheWriter() instanceof GfxdCacheWriter) {
              executeCleanup(stmt, "call sys.remove_writer('"
                  + c.getSchemaName() + "', '" + c.getTableName() + "')");
            }
          }
        }
      } catch (Exception ex) {
        getGlobalLogger().error("unexpected exception in cleanup", ex);
      }
    }
  }

  static final class DoCleanupOnAll2 extends SerializableRunnable {

    @Override
    public void run() {
      try {
        final GemFireCacheImpl cache;
        if (GemFireStore.getBootedInstance() != null
            && (cache = Misc.getGemFireCacheNoThrow()) != null
            && GemFireXDUtils.getMyVMKind().isAccessorOrStore()) {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          // also GemFireXD specific objects; first gateway senders/listeners
          for (AsyncEventQueue queue : cache.getAsyncEventQueues()) {
            executeCleanup(stmt, "drop asynceventlistener if exists \"" + queue.getId()
                + '"');
          }
          for (GatewaySender sender : cache.getGatewaySenders()) {
            executeCleanup(stmt, "drop gatewaysender if exists \"" + sender.getId()
                + '"');
          }
        }
      } catch (Exception ex) {
        getGlobalLogger().error("unexpected exception in cleanup", ex);
      }
    }
  }

  static final class DoCleanupOnAll3 extends SerializableRunnable {

    @Override
    public void run() {
      try {
        final GemFireCacheImpl cache;
        if (GemFireStore.getBootedInstance() != null
            && (cache = Misc.getGemFireCacheNoThrow()) != null
            && GemFireXDUtils.getMyVMKind().isAccessorOrStore()) {
          Connection conn = TestUtil.getConnection();
          Statement stmt = conn.createStatement();
          // also GemFireXD specific objects; last receivers and others
          for (GatewayReceiver receiver : cache.getGatewayReceivers()) {
            executeCleanup(stmt, "drop gatewayreceiver \"" + receiver.getId()
                + '"');
          }
          for (HDFSStoreImpl hdfsStore : cache.getAllHDFSStores()) {
            executeCleanup(stmt, "drop hdfsstore \"" +
                hdfsStore.getName() + '"');
          }
          for (DiskStoreImpl ds : cache.listDiskStores()) {
            if (!GfxdConstants.GFXD_DEFAULT_DISKSTORE_NAME.equals(ds.getName())
                && !GfxdConstants.GFXD_DD_DISKSTORE_NAME.equals(ds.getName())
                && !GfxdConstants.SNAPPY_DEFAULT_DELTA_DISKSTORE.equals(ds.getName())) {
              executeCleanup(stmt, "drop diskstore \"" + ds.getName() + '"');
            }
          }
          // reset critical/eviction heap percentage
          if (GfxdSystemProcedures.GET_EVICTION_HEAP_PERCENTAGE() != 0.0f) {
            executeCleanup(stmt,
                "call sys.set_eviction_heap_percentage_sg(0.0, NULL)");
          }
          if (GfxdSystemProcedures.GET_CRITICAL_HEAP_PERCENTAGE() != 0.0f) {
            executeCleanup(stmt,
                "call sys.set_critical_heap_percentage_sg(0.0, NULL)");
          }
        }
      } catch (Exception ex) {
        getGlobalLogger().error("unexpected exception in cleanup", ex);
      }
    }
  }

  @Override
  public void tearDown2() throws Exception {
    // cleanup all the items in the datadictionary
    GemFireXDUtils.IS_TEST_MODE = false;
    
    // start with the query observers
    boolean[] requiresCleanup;
    Map<?, ?> requiresCleanupMap;
    DoPreCleanup preCleanup = new DoPreCleanup();
    requiresCleanup = (boolean[])preCleanup.call();
    requiresCleanupMap = invokeInEveryVM(preCleanup);

    // first do the table related callbacks on all nodes
    DoCleanupOnAll1 doCleanup1 = new DoCleanupOnAll1();
    performCleanupOnAll(doCleanup1, requiresCleanup, requiresCleanupMap, 0);
    // then tables/indexes/schemas
    DoCleanup doCleanup = new DoCleanup();
    Boolean done = (Boolean)doCleanup.call();
    if (done != Boolean.TRUE) {
      for (int h = 0; h < Host.getHostCount(); h++) {
        Host host = Host.getHost(h);
        for (int v = 0; v < host.getVMCount(); v++) {
          VM vm = host.getVM(v);
          done = (Boolean)vm.invoke(doCleanup);
          if (done == Boolean.TRUE) {
            break;
          }
        }
        if (done == Boolean.TRUE) {
          break;
        }
      }
    }
    // finally global async listners, diskstores etc.
    DoCleanupOnAll2 doCleanup2 = new DoCleanupOnAll2();
    performCleanupOnAll(doCleanup2, requiresCleanup, requiresCleanupMap, 1);
    DoCleanupOnAll3 doCleanup3 = new DoCleanupOnAll3();
    performCleanupOnAll(doCleanup3, requiresCleanup, requiresCleanupMap, 2);

    if (reduceLogging() != null
        || System.getProperty("gemfire.log-level") != null) {
      reduceLogLevelForTest(null);
    }

    try {
      shutDownAll();
      super.tearDown2();
    } finally {
      // many tests create the "newDB" derby DB, so this is a catch-all
      // to delete the directory; ignore any exceptions here
      final String[] dbs = new String[] { "newDB", "newDB2", "newDB3" };
      final ModuleFactory mon = Monitor.getMonitor();
      final HeaderPrintWriter pw;
      if (mon != null && mon.getSystemStreams() != null) {
        pw = mon.getSystemStreams().stream();
      }
      else {
        pw = null;
      }
      if (pw != null) {
        pw.println(
            "<ExpectedException action=add>SQLException</ExpectedException>");
      }
      for (String db : dbs) {
        if (pw != null) {
          try {
            DriverManager.getConnection("jdbc:derby:" + db + ";shutdown=true");
          } catch (SQLException sqle) {
            // ignored
          }
        }
        try {
          TestUtil.deleteDir(new File(db));
        } catch (Exception ex) {
          // ignored
        }
      }
      if (pw != null) {
        pw.println(
            "<ExpectedException action=remove>SQLException</ExpectedException>");
      }
    }
    // reset off-heap flag
    configureDefaultOffHeap(false);
  }

  private void performCleanupOnAll(SerializableRunnable doCleanup,
      boolean[] requiresCleanup, Map<?, ?> requiresCleanupMap,
      int requiresCleanIndex) {
    if (requiresCleanup[requiresCleanIndex]) {
      doCleanup.run();
    }
    Object requiresCleanupObj;
    for (int h = 0; h < Host.getHostCount(); h++) {
      Host host = Host.getHost(h);
      for (int v = 0; v < host.getVMCount(); v++) {
        VM vm = host.getVM(v);
        if ((requiresCleanupObj = requiresCleanupMap.get(vm)) != null
            && ((boolean[])requiresCleanupObj)[requiresCleanIndex]) {
          vm.invoke(doCleanup);
        }
      }
    }
  }

  protected static final void executeCleanup(Statement stmt, String sql) {
    try {
      stmt.execute(sql);
    } catch (SQLException sqle) {
      // ignore
      getGlobalLogger().warn("unexpected exception", sqle);
    }
  }

  /**
   * Shutdown this VM.
   */
  public static void vmShutDown(String className) throws Exception {
    final Class<?> c = Class.forName(className);
    ((DistributedSQLTestBase)c.getConstructor(String.class).newInstance("tmp"))
        .vmTearDown();
  }

  /**
   * This is shutdown for one VM that can be overriden by tests.
   */
  protected void vmTearDown() throws Exception {
    try {
      // remove expected derby exceptions
      for (String exStr : expectedDerbyExceptions) {
        final String expectedExRemoveStr = "<ExpectedException action=remove>"
            + exStr + "</ExpectedException>";
        System.out.println(expectedExRemoveStr);
        SanityManager.GET_DEBUG_STREAM().println(expectedExRemoveStr);
      }
      expectedDerbyExceptions.clear();
      numConnectionsOpened.set(0);
      numConnectionsClosed.set(0);
      // create the datadictionary directory if not present since GFE shutdown
      // now requires it
      deleteOrCreateDataDictionaryDir(true);
      System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
      System.clearProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
      final Properties props = new Properties();
      setCommonProperties(props, 0, null, null);
      // shutdown the current VM
      TestUtil.shutDown(props);
      deleteTestArtifacts();
      TestUtil.deletePersistentFiles = false;
    } finally {
      // clear static variables
      TestUtil.clearStatics();
    }
  }

  protected void baseShutDownAll() throws Exception {
    // Log any errors in shutDown as errors but do not throw exception to
    // avoid masking an actual problem in the test, if any
    //HashMap<AsyncInvocation, VM> stopList = new HashMap<AsyncInvocation, VM>();
    for (int hostNum = 0; hostNum < Host.getHostCount(); ++hostNum) {
      Host host = Host.getHost(hostNum);
      for (int vmNum = 0; vmNum < host.getVMCount(); ++vmNum) {
        VM vm = host.getVM(vmNum);
        try {
          getLogWriter().info("Shutting down " + vm);
          vm.invoke(DistributedSQLTestBase.class, "vmShutDown",
              new Object[] { this.getClass().getName() });
          // TODO: merge: below is causing jgroups thread leaks after recent merge
          /*
          stopList.put(vm.invokeAsync(this.getClass(), "vmShutDown",
              new Object[] { this.getClass() }), vm);
          */
        } catch (Exception ex) {
          getLogWriter().error("Failed in shutdown for " + vm, ex);
        }
      }
    }
    try {
      vmShutDown(this.getClass().getName());
    } catch (Exception ex) {
      getLogWriter().error("Failed in shutdown for controller VM", ex);
    }
    // TODO: merge: below is causing jgroups thread leaks after recent merge
    /*
    for (Map.Entry<AsyncInvocation, VM> async : stopList.entrySet()) {
      try {
        joinAsyncInvocation(async.getKey(), async.getValue());
      } catch (Exception ex) {
        getLogWriter().error("Failed in shutdown for VM " + async.getValue(),
            ex);
      }
    }
    */
    // clear the VM lists
    this.clientVMs.clear();
    this.serverVMs.clear();
    this.members.clear();
    this.locatorString = null;
    TestConfiguration.odbcIni.delete();
  }

  public void shutDownAll() throws Exception {
    baseShutDownAll();
  }

  public static void deleteStrayDataDictionaryDir() {
    deleteStrayDataDictionaryDir(true);
  }

  static void deleteStrayDataDictionaryDir(boolean force) {
    String parent = System.getProperty(GfxdConstants.GFXD_PREFIX +
        com.pivotal.gemfirexd.Attribute.SYS_PERSISTENT_DIR);
    File dir = null;
    if (parent == null || parent.equals("")) {
      if (!force) {
        return;
      }
      dir = new File("datadictionary");
    }
    else {
      dir = new File(parent, "datadictionary");
    }
    boolean result = TestUtil.deleteDir(dir);
    TestUtil.getLogger().info(
        "For Test: " + currentClassName + ":" + currentTestName
            + " found and deleted stray datadictionarydir at: "
            + dir.toString() + " : " + result);
  }

  public static void deleteDataDictionaryDir() {
    deleteOrCreateDataDictionaryDir(false);
  }

  public static void deleteGlobalIndexCachinDir() {
    File dir = new File(getSysDiskDir(), "globalIndex");
    boolean result = TestUtil.deleteDir(dir);
    getGlobalLogger().info(
        "For Test: " + currentClassName + ":" + currentTestName
            + " found and deleted globalIndex cache at: " + dir.toString()
            + " : " + result);
    // create the global index cache directory again for subsequent tests
    if (!dir.mkdirs() && !dir.isDirectory()) {
      throw new DiskAccessException("Could not create directory for "
          + " global index caching: " + dir.getAbsolutePath(),
          (Region<?, ?>)null);
    }
  }

  protected static void deleteOrCreateDataDictionaryDir(boolean create) {
    File dir = new File(getSysDiskDir(), "datadictionary");
    if (!create) {
      boolean result = TestUtil.deleteDir(dir);
      getGlobalLogger().info(
          "For Test: " + currentClassName + ":" + currentTestName
              + " found and deleted datadictionarydir at: " + dir.toString()
              + " : " + result);
      deleteStrayDataDictionaryDir(false);
    }
    // create the datadictionary directory again for subsequent tests
    if (!dir.mkdirs() && !dir.isDirectory()) {
      throw new DiskAccessException("Could not create directory for "
          + " datadictionary: " + dir.getAbsolutePath(), (Region<?, ?>)null);
    }
    getGlobalLogger().info(
        "For Test: " + currentClassName + ":" + currentTestName
            + " created datadictionarydir at: " + dir.toString());
  }

  protected final VM getVM(int vmNum) {
    if (vmNum < 0) {
      return getServerVM(-vmNum);
    }
    return getClientVM(vmNum);
  }

  public void waitForDDCreation(int vmNum) {
    final VM vm = getVM(vmNum);
    if (vm != null) {
      vm.invoke(DistributedSQLTestBase.class, "_waitForDDCreation");
    }
    else {
      _waitForDDCreation();
    }
  }

  public static void _waitForDDCreation() {
    waitForCriterion(new WaitCriterion() {

      @Override
      public String description() {
        return "waiting for DataDictionary region to initialize";
      }

      @Override
      public boolean done() {
        final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        final LocalRegion region;
        return cache != null && (region = (LocalRegion)cache.getRegion(
            GemFireStore.DDL_STMTS_REGION)) != null && region.isInitialized();
      }
    }, 120000, 500, true);
  }

  public static void deleteTestArtifacts() {
    try {
      deleteDataDictionaryDir();
      deleteGlobalIndexCachinDir();
      final DistributedSQLTestBase test = testInstance;
      final String[] testSpecificDirs;
      if (test != null && (testSpecificDirs = test
          .testSpecificDirectoriesForDeletion()) != null) {
        for (String file : testSpecificDirs) {
          File dir = new File(getSysDiskDir(), file);
          boolean result = TestUtil.deleteDir(dir);
          getGlobalLogger().info(
              "For Test: " + currentClassName + ":" + currentTestName
                  + " found and deleted test specific directory at: "
                  + dir.toString() + " : " + result);
        }
      }
    } catch (Exception ex) {
      throw new TestException("unexpected exception", ex);
    }
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  public static void delete(File file) {
    if (!file.exists()) {
      return;
    }
    if (file.isDirectory()) {
      if (file.list().length == 0) {
        file.delete();
      } else {
        File[] files = file.listFiles();
        if (files != null) {
          for (File f : files) {
            delete(f);
          }
        }
        file.delete();
      }
    } else {
      file.delete();
    }
  }

  protected void execute(VM executeVM, String jdbcSQL, boolean doVerify,
      String goldenTextFile, String nodeID, String schemaName,
      String tableName, RegionAttributes<?, ?> attrs, boolean usePrepStmt,
      boolean checkTypeInfo, boolean consumeResults) throws SQLException,
      IOException, ParserConfigurationException, SAXException,
      TransformerException {
    if (jdbcSQL == null) {
      getLogWriter().info(
          "Executing verifyRegionProperties for table [" + schemaName + "."
              + tableName + "] on VM: " + executeVM);
      if (executeVM != null) {
        executeVM.invoke(TestUtil.class, "verifyRegionProperties",
            new Object[] { schemaName, tableName,
                regionAttributesToXML(attrs, executeVM) });
      }
      else {
        TestUtil.verifyRegionProperties(schemaName, tableName,
            regionAttributesToXML(attrs, null));
      }
    }
    else if (doVerify) {
      getLogWriter().info(
          "Executing sqlExecuteVerify [" + jdbcSQL + "] on VM: " + executeVM);
      if (goldenTextFile != null || nodeID != null) {
        // Execute and compare the results
        if (executeVM != null) {
          executeVM
              .invoke(TestUtil.class, "sqlExecuteVerifyText",
                  new Object[] { jdbcSQL, goldenTextFile, nodeID,
                      Boolean.valueOf(usePrepStmt),
                      Boolean.valueOf(checkTypeInfo) });
        }
        else {
          TestUtil.sqlExecuteVerifyText(jdbcSQL, goldenTextFile, nodeID,
              Boolean.valueOf(usePrepStmt), Boolean.valueOf(checkTypeInfo));
        }
      }
      else {
        // Check that there is at least one result
        if (executeVM != null) {
          executeVM.invoke(TestUtil.class, "sqlExecuteVerify", new Object[] {
              jdbcSQL, Boolean.valueOf(usePrepStmt) });
        }
        else {
          TestUtil.sqlExecuteVerify(jdbcSQL, Boolean.valueOf(usePrepStmt));
        }
      }
    }
    else {
      getLogWriter().info(
          "Executing sqlExecute [" + jdbcSQL + "] on VM: " + executeVM);
      if (executeVM != null) {
        executeVM.invoke(TestUtil.class, "sqlExecute", new Object[] { jdbcSQL,
            Boolean.valueOf(usePrepStmt), Boolean.valueOf(consumeResults) });
      }
      else {
        TestUtil.sqlExecute(jdbcSQL, Boolean.valueOf(usePrepStmt));
      }
    }
  }

  protected void execute(List<VM> executeVMs, String jdbcSQL, boolean doVerify,
      String goldenTextFile, String nodeID, String schemaName,
      String tableName, RegionAttributes<?, ?> attrs, boolean usePrepStmt,
      boolean checkTypeInfo) throws SQLException, IOException,
      ParserConfigurationException, SAXException, TransformerException {
    for (VM vm : executeVMs) {
      execute(vm, jdbcSQL, doVerify, goldenTextFile, nodeID, schemaName,
          tableName, attrs, usePrepStmt, checkTypeInfo, false);
    }
  }

  /**
   * execute with retries in case of node failures for transactions but not for
   * non-transactional case
   */
  protected static int executeUpdate(Statement stmt, String sql)
      throws SQLException {
    while (true) {
      try {
        if (sql != null) {
          return stmt.executeUpdate(sql);
        }
        else {
          return ((PreparedStatement)stmt).executeUpdate();
        }
      } catch (SQLException sqle) {
        Connection conn = stmt.getConnection();
        if (conn.getTransactionIsolation() == Connection.TRANSACTION_NONE) {
          throw sqle;
        }
        String sqlState = sqle.getSQLState();
        if (!"40XD0".equals(sqlState) && !"40XD2".equals(sqlState)) {
          throw sqle;
        }
      }
    }
  }

  public static String getRegionsStr(Cache gfCache) {
    StringBuilder sb = new StringBuilder();
    sb.append("Available regions: \n");
    for (Object regionObj : gfCache.rootRegions()) {
      Region<?, ?> region = (Region<?, ?>)regionObj;
      sb.append("\tRoot region: " + region.getFullPath() + "\n");
      for (Object subRegionObj : region.subregions(true)) {
        Region<?, ?> subRegion = (Region<?, ?>)subRegionObj;
        sb.append("\t\tSubregion: " + subRegion.getFullPath() + "\n");
      }
    }
    return sb.toString();
  }

  protected final ArrayList<VM> getVMs(final int[] clientNums,
      final int[] serverNums) {
    return getVMs(clientNums, serverNums, false);
  }

  protected final ArrayList<VM> getVMs(final int[] clientNums,
      final int[] serverNums, final boolean skipNonExisting) {
    ArrayList<VM> vms = new ArrayList<VM>();
    if (clientNums != null) {
      final int numClients = this.clientVMs.size();
      for (int clientNum : clientNums) {
        if (skipNonExisting && clientNum > numClients) {
          continue;
        }
        assertTrue("Client number should be positive and not exceed total "
            + "number of clients: " + this.clientVMs.size(), clientNum > 0
            && clientNum <= numClients);
        vms.add(this.clientVMs.get(clientNum - 1));
      }
    }
    if (serverNums != null) {
      final int numServers = this.serverVMs.size();
      for (int serverNum : serverNums) {
        if (skipNonExisting && serverNum > numServers) {
          continue;
        }
        assertTrue("Server number should be positive and not exceed total "
            + "number of servers: " + this.serverVMs.size(), serverNum > 0
            && serverNum <= numServers);
        vms.add(this.serverVMs.get(serverNum - 1));
      }
    }
    return vms;
  }

  public static int getDefaultLocalMaxMemory() {
    return PartitionAttributesFactory.LOCAL_MAX_MEMORY_DEFAULT;
  }

  private String regionAttributesToXML(RegionAttributes<?, ?> attrs, VM vm) {
    // adjust default local-max-memory as per target VM size
    PartitionAttributesImpl pa;
    if (vm != null && attrs != null && (pa = (PartitionAttributesImpl)attrs
        .getPartitionAttributes()) != null && !pa.getEnableOffHeapMemory() &&
        !pa.hasLocalMaxMemory()) {
      int localMaxMemory = (Integer)vm.invoke(getClass(),
          "getDefaultLocalMaxMemory");
      pa.setLocalMaxMemory(localMaxMemory);
    }
    return TestUtil.regionAttributesToXML(attrs);
  }

  public static String getSysDiskDir() {
    try {
      return getSysDirName();
    } catch (Exception ex) {
      throw new TestException("unexpected exception", ex);
    }
  }

  public String getTestLogPrefix() {
    final String sysDirName = getSysDirName();
    final String testName = getTestLogNamePrefix();
    return sysDirName + '/' + testName;
  }

  public String getTestLogNamePrefix() {
    return getTestClass().getName() + "-" + getTestName();
  }

  /**
   * check for foreign key violation when executing the given SQL; negative
   * vmNum indicates server VM while positive is for client VM
   */
  protected void checkFKViolation(int vmNum, String sql) throws Exception {
    checkKeyViolation(vmNum, sql, "23503", "foreign key violation");
  }

  /**
   * check for primary or unique key violation when executing the given SQL;
   * negative vmNum indicates server VM while positive is for client VM
   */
  protected void checkKeyViolation(int vmNum, String sql) throws Exception {
    checkKeyViolation(vmNum, sql, "23505", "primary or unique key violation");
  }

  /**
   * check for a key violation when executing the given SQL; negative vmNum
   * indicates server VM while positive is for client VM
   */
  protected void checkKeyViolation(int vmNum, String sql, String expectedState,
      String violationString) throws Exception {
    try {
      if (vmNum < 0) {
        serverSQLExecute(-vmNum, sql);
      }
      else {
        clientSQLExecute(vmNum, sql);
      }
      fail("should have got a " + violationString + " exception for SQL: "
          + sql);
    } catch (SQLException ex) {
      if (!expectedState.equals(ex.getSQLState())) {
        throw ex;
      }
    } catch (RMIException ex) {
      if (ex.getCause() instanceof SQLException) {
        SQLException sqlEx = (SQLException)ex.getCause();
        if (!expectedState.equals(sqlEx.getSQLState())) {
          throw ex;
        }
      }
      else {
        throw ex;
      }
    }
  }

  /**
   * Returns the VM object for a given GemFire <code>DistributedMember</code>.
   *
   * @param member the GemFire distributed member
   * @return VM the VM object representing the member
   */
  public VM getHostVMForMember(DistributedMember member) {
    return this.members.get(member);
  }

  public DistributedMember getMemberForVM(VM vm) {
    for (Map.Entry<DistributedMember, VM> entry : this.members.entrySet()) {
      if (vm == entry.getValue()) {
        return entry.getKey();
      }
    }
    return null;
  }

  // Methods to check for query execution

  protected static volatile boolean isQueryExecutedOnNode = false;

  public static Boolean getQueryStatus() {
    try {
      getGlobalLogger().info(
          "Getting query status for this VM: " + isQueryExecutedOnNode);
      return Boolean.valueOf(isQueryExecutedOnNode);
    } finally {
      isQueryExecutedOnNode = false;
    }
  }

  protected void checkQueryExecution(final boolean oneOf, VM... vms) {
    SerializableRunnable checkNoQuery = new SerializableRunnable(
        "check for no query execution") {
      @Override
      public void run() throws CacheException {
        getLogWriter().info("Checking for no execution of query on this VM");
        assertFalse("expected query to not execute on this VM "
            + Misc.getGemFireCache().getMyId(), isQueryExecutedOnNode);
      }
    };
    Set<VM> allServers = new HashSet<VM>(this.serverVMs);
    Boolean res = Boolean.FALSE;
    for (VM vm : vms) {
      Boolean tres;
      if(vm == null) {
        //local
        tres = getQueryStatus();
      }
      else  {
        tres = (Boolean)vm.invoke(DistributedSQLTestBase.class,
          "getQueryStatus");
      }
      if (oneOf) {
        if (res.booleanValue()) {
          assertFalse("did not expect query to execute on this VM: " + vm, tres
              .booleanValue());
        }
        else {
          res = tres;
        }
      }
      else {
        assertTrue("expected query to execute on this VM: " + vm, tres
            .booleanValue());
      }
      if (tres.booleanValue()) {
        allServers.remove(vm);
      }
    }
    if (oneOf) {
      assertTrue("expected query to execute on exactly one VM", res
          .booleanValue());
    }
    for (VM vm : allServers) {
      vm.invoke(checkNoQuery);
    }
    // also check for no query execution in the controller VM
    checkNoQuery.run();
  }

  protected void setupObservers(VM[] dataStores, final SelectQueryInfo[] sqi) {
    // Create a prepared statement for the query & store the queryinfo so
    // obtained to get region reference
    // set up a sql query observer in client VM
    isQueryExecutedOnNode = false;
    GemFireXDQueryObserverHolder
        .setInstance(new GemFireXDQueryObserverAdapter() {
          @Override
          public void queryInfoObjectFromOptmizedParsedTree(QueryInfo qi,
              GenericPreparedStatement gps, LanguageConnectionContext lcc) {
            sqi[0] = (SelectQueryInfo)qi;
          }
        });

    // set up a sql query observer in server VM to keep track of whether the
    // query got executed on the node or not
    SerializableRunnable setObserver = new SerializableRunnable(
        "Set GemFireXDObserver on DataStore Node") {
      @Override
      public void run() throws CacheException {
        try {
          isQueryExecutedOnNode = false;
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter() {
                @Override
                public void beforeQueryExecutionByPrepStatementQueryExecutor(
                    GfxdConnectionWrapper wrapper,
                    EmbedPreparedStatement pstmt, String query) {
                  getLogWriter().info(
                      "Invoked DistributedSQLTestBase query "
                          + "observer for prepared statement query: " + query);
                  // skip for queries on SYS.MEMORYANALYTICS which are now
                  // done periodically by GFXD mbeans thread
                  if (query == null && pstmt.getGPS() != null) {
                    query = pstmt.getGPS().getSource();
                  }
                  if (query == null || (!query.contains("SYS.MEMORYANALYTICS")
                      && !query.contains("SYS.MEMBERS"))) {
                    isQueryExecutedOnNode = true;
                  }
                }

                @Override
                public void beforeQueryExecutionByStatementQueryExecutor(
                    GfxdConnectionWrapper wrapper, EmbedStatement stmt,
                    String query) {
                  getLogWriter().info(
                      "Invoked DistributedSQLTestBase query "
                          + "observer for statement query: " + query);
                  // skip for queries on SYS.MEMORYANALYTICS which are now
                  // done periodically by GFXD mbeans thread
                  if (query == null || (!query.contains("SYS.MEMORYANALYTICS")
                      && !query.contains("SYS.MEMBERS"))) {
                    isQueryExecutedOnNode = true;
                  }
                }

                @Override
                public String toString() {
                  return "DistributedSQLTestBase query observer";
                }
              });
        } catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };

    for (VM dataStore : dataStores) {
      // local VM
      if (dataStore == null) {
        setObserver.run();
        continue;
      }
      dataStore.invoke(setObserver);
    }
  }

  /**
   * 
   * @param sqi
   * @param prunedNodes
   * @param noOfPrunedNodes
   * @param noOfNoExecQueryNodes
   *          Query shouldn't get executed on exculding client nodes.
   */
  protected void verifyQueryExecution(final SelectQueryInfo sqi,
      Set<DistributedMember> prunedNodes, Set<DistributedMember> noQuerynodes,
      int noOfPrunedNodes, int noOfNoExecQueryNodes) {

    SerializableRunnable validateQueryExecution = new SerializableRunnable(
        "validate node has executed the query") {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter());
          assertTrue(isQueryExecutedOnNode);
          isQueryExecutedOnNode = false;

        } catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
    SerializableRunnable validateNoQueryExecution = new SerializableRunnable(
        "validate node has NOT executed the query") {
      @Override
      public void run() throws CacheException {
        try {
          GemFireXDQueryObserverHolder
              .setInstance(new GemFireXDQueryObserverAdapter());
          assertFalse(isQueryExecutedOnNode);
          isQueryExecutedOnNode = false;

        } catch (Exception e) {
          throw new CacheException(e) {
          };
        }
      }
    };
    getLogWriter().info(" Non Query nodes size= " + noQuerynodes.size());

    Iterator<DistributedMember> itr2 = noQuerynodes.iterator();
    while (itr2.hasNext()) {
      getLogWriter().info(" Non Query member = " + itr2.next());
    }

    getLogWriter().info(
        "Number of members found after prunning =" + prunedNodes.size());
    Iterator<DistributedMember> itr1 = prunedNodes.iterator();
    while (itr1.hasNext()) {
      getLogWriter().info("Prunned member=" + itr1.next());
    }
    assertEquals(noOfPrunedNodes, prunedNodes.size());

    assertEquals(noOfNoExecQueryNodes, noQuerynodes.size());
    Iterator<DistributedMember> itr = noQuerynodes.iterator();
    while (itr.hasNext()) {
      DistributedMember member = itr.next();
      VM nodeVM = this.getHostVMForMember(member);
      assertNotNull(nodeVM);
      nodeVM.invoke(validateNoQueryExecution);

    }

    // Nodes executing the query

    itr = prunedNodes.iterator();
    while (itr.hasNext()) {
      DistributedMember member = itr.next();
      VM nodeVM = this.getHostVMForMember(member);
      assertNotNull(nodeVM);
      nodeVM.invoke(validateQueryExecution);

    }
  }

  protected void derbyCleanup(Statement derbyStmt, Connection derbyConn,
      NetworkServerControl server) throws Exception {

    if (derbyConn == null) {
      return;
    }
    if (derbyStmt == null) {
      derbyStmt = derbyConn.createStatement();
    }
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
    derbyConn.commit();

    derbyStmt.execute("drop procedure validateTestEnd ");
    derbyConn.commit();

    derbyConn.close();
    
    cleanDerbyArtifacts(derbyStmt, new String[] {}, new String[] {},
        new String[] { "TESTTABLE" }, derbyConn);
    // might get ShutdownExceptions in derby connection close
    addExpectedDerbyException(ShutdownException.class.getName());
    try {
      DriverManager.getConnection("jdbc:derby:;shutdown=true");
    }
    catch (SQLException sqle) {
      if (sqle.getMessage().indexOf("shutdown") == -1) {
        sqle.printStackTrace();
        throw sqle;
      }
    }
    if (server != null) {
      try {
        server.shutdown();
      }
      catch (Exception ex) {
        getLogWriter().error("unexpected exception", ex);
      }
    }
  }

  protected void cleanDerbyArtifacts(Statement derbyStmt, String[] derbyProcs,
      String derbyTriggers[], String derbyTables[], Connection conn)
      throws Exception {
    if (derbyStmt != null) {
      if (derbyProcs.length > 0) {
        // might get procedure not found errors
        addExpectedDerbyException("42Y55");
      }
      for (String derbyProc : derbyProcs) {
        try {
          derbyStmt.execute("drop procedure " + derbyProc);
        }
        catch (SQLException ex) {
          // deliberately ignored
        }
      }
      if (derbyTriggers.length > 0) {
        // might get trigger not found errors
        addExpectedDerbyException("42X94");
      }
      for (String derbyTrigg : derbyTriggers) {
        try {
          derbyStmt.execute("drop trigger " + derbyTrigg);
        }
        catch (SQLException ex) {
        }
        // deliberately ignored
      }
      /*
       * final String[] tables = new String[] { "testtable", "testtable1",
       * "testtable2", "testtable3" };
       */
      for (String table : derbyTables) {
        try {
          derbyStmt.execute("drop table " + table);
        }
        catch (SQLException ex) {
          // deliberately ignored
        }
      }
    }
    if (conn != null) {
      try {
        conn.close();
      } catch (SQLException ex) {
        // deliberately ignored
      }
    }
  }

  protected final void addExpectedDerbyException(String exStr) {
    final String expectedExStr = "<ExpectedException action=add>" + exStr
        + "</ExpectedException>";
    System.out.println(expectedExStr);
    SanityManager.GET_DEBUG_STREAM().println(expectedExStr);
    expectedDerbyExceptions.add(exStr);
  }

  protected final void validateResults(Statement derbyStmt,
      String validationQuery, int netPort, boolean ignoreTypeInfo) throws Exception {

    Statement stmt = TestUtil.getStatement();
    ResultSet derbyRS = derbyStmt.executeQuery(validationQuery);
    ResultSet gfxdRS = stmt.executeQuery(validationQuery);
    // Convert the ResultSet to an XML Element
    Element resultElement = Misc.resultSetToXMLElement(gfxdRS, true, ignoreTypeInfo);
    Element expectedElement = Misc.resultSetToXMLElement(derbyRS, true, ignoreTypeInfo);
    Map<String, Integer> resultMap = TestUtil
        .xmlElementToFrequencyMap(resultElement);
    Map<String, Integer> expectedResultMap = TestUtil
        .xmlElementToFrequencyMap(expectedElement);
    String resultStr = Misc.serializeXML(resultElement);
    String expectedStr = Misc.serializeXML(expectedElement);

    getLogWriter().info(
        "sqlExecuteVerifyText: The result XML is:\n" + resultStr);
    getLogWriter().info(
        "sqlExecuteVerifyText: The expected XML is:\n" + expectedStr);
    
    if (!TestUtil.compareFrequencyMaps(expectedResultMap, resultMap)) {
      getLogWriter().info(
          "sqlExecuteVerifyText: The result XML is:\n" + resultStr);
      getLogWriter().info(
          "sqlExecuteVerifyText: The expected XML is:\n" + expectedStr);
      fail("Expected string in derby does not "
          + "match the result from GemFireXD.");
    }
    /*
    List<Struct> derbyList = ResultSetHelper.asList(derbyRs,
        ResultSetHelper.getStructType(derbyRs), true /* is derby*);
    List<Struct> gfxdList = ResultSetHelper.asList(gfxdRS,
        ResultSetHelper.getStructType(gfxdRS), false);
    ResultSetHelper.compareResultSets(derbyList, gfxdList);
    */
  }

  protected static SerializableRunnable getDiskStoreCreator(
      final String diskStore) {
    SerializableRunnable csr = new SerializableRunnable(
        "diskstore creator") {
      @Override
      public void run() throws CacheException {
        GemFireCacheImpl cache = Misc.getGemFireCache();
        if (cache.findDiskStore(diskStore) == null) {
          String path = "." + fileSeparator + "test_dir";
          File file = new File(path);
          if (!file.mkdirs() && !file.isDirectory()) {
            throw new DiskAccessException("Could not create directory for "
                + " default disk store : " + file.getAbsolutePath(),
                (Region<?, ?>)null);
          }
          try {
            Connection conn;
            conn = TestUtil.getConnection();
            Statement stmt = conn.createStatement();
            stmt.execute("Create DiskStore " + diskStore + " '" + path + "'");
            conn.close();
          } catch (SQLException e) {
            throw GemFireXDRuntimeException.newRuntimeException(null, e);
          }
        }
      }
    };
    return csr;
  }

  public static void reset() {
    isQueryExecutedOnNode = false;
  }

  protected String[] testSpecificDirectoriesForDeletion() {
    return null;
  }
  
  protected boolean isDefaultOffHeapConfigured() {
    return this.configureDefaultOffHeap;
  }

  protected void verifyDeltaSizeFromStats(VM vm, final int expectedKeyNum,
      final int expectedTXIdNum, final int expectedDeltaGIINum,
      final String regionPath) {
    SerializableRunnable verify = new SerializableRunnable() {
      @Override
      public void run() {
        // verify from CachePerfStats that certain amount of keys in delta
        LocalRegion lr = (LocalRegion)Misc.getRegion(regionPath, true, false);
        LogWriter logger = lr.getLogWriterI18n().convertToLogWriter();
        CachePerfStats stats = lr.getRegionPerfStats();

        // we saved GII completed count in RegionPerfStats only
        int size = stats.getGetInitialImageKeysReceived();
        logger.info("Delta contains: " + size + " keys");
        assertEquals(expectedKeyNum, size);

        size = stats.getGetInitialImageTransactionsReceived();
        logger.info("Delta contains: " + size + " TXIds");
        assertEquals(expectedTXIdNum, size);

        int num = stats.getDeltaGetInitialImagesCompleted();
        logger.info("Delta GII completed: " + num + " times");
        assertEquals(expectedDeltaGIINum, num);
      }
    };
    vm.invoke(verify);
  }
}
