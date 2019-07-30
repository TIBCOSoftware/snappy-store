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
package com.pivotal.gemfirexd.internal.engine;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.net.URI;
import java.sql.CallableStatement;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.gemstone.gemfire.distributed.internal.AbstractDistributionConfig;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.distributed.internal.InternalDistributedSystem;
import com.gemstone.gemfire.distributed.internal.InternalLocator;
import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.cache.CacheServerLauncher;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.gemstone.junit.UnitTest;
import com.pivotal.gemfirexd.*;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.reference.Property;
import com.pivotal.gemfirexd.internal.iapi.services.monitor.Monitor;
import com.pivotal.gemfirexd.internal.impl.io.DirFile;
import com.pivotal.gemfirexd.internal.shared.common.sanity.SanityManager;
import com.pivotal.gemfirexd.tools.GfxdUtilLauncher;
import com.pivotal.gemfirexd.tools.internal.GfxdServerLauncher;
import io.snappydata.jdbc.ClientDriver;
import io.snappydata.test.dunit.DistributedTestBase;
import junit.framework.TestSuite;
import junit.textui.TestRunner;

@SuppressWarnings("ResultOfMethodCallIgnored")
public class FabricServerTest extends TestUtil implements UnitTest {

  public FabricServerTest(String name) {
    super(name);
  }

  @Override
  protected void setUp() throws Exception {
    try {
      if (GemFireStore.getBootingInstance() != null) {
        shutDown();
      }
      currentUserName = null;
      currentUserPassword = null;
    } finally {
      super.setUp();
    }
  }

  @Override
  protected void tearDown() throws Exception {
    try {
      shutDown();
      currentUserName = null;
      currentUserPassword = null;
    } finally {
      super.tearDown();
      // also delete the properties file
      new File(PROP_FILE_NAME).delete();
    }
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(FabricServerTest.class));
  }

  private static final String PROP_FILE_NAME = "snappydata-store-server.properties";

  /**
   * Test for the NativeCalls implementation methods.
   */
  public void testNativeCalls() throws Exception {
    // test for process ID
    final NativeCalls nc = NativeCalls.getInstance();
    final int myProcId = nc.getProcessId();
    assertTrue(myProcId != 0);
    final String jvmName = ManagementFactory.getRuntimeMXBean().getName();
    assertTrue("Unexpected my processID=" + myProcId + ", runtime name="
        + jvmName, jvmName.startsWith(String.valueOf(myProcId)));

    // isProcessActive
    assertTrue(nc.isProcessActive(myProcId));
    final String productDir = System.getProperty("GEMFIREXD");
    assertTrue(productDir != null && productDir.length() > 0);
    // spawn off a new server and check its status
    final DirFile file = new DirFile("nc-server");
    file.deleteAll();
    assertTrue("failed to create directory " + file.getAbsolutePath(),
        file.mkdir());
    final String workingDir = file.getAbsolutePath();
    final String utilLauncher;
    final char pathSep = DirFile.separatorChar;
    if (nc.getOSType().isWindows()) {
      utilLauncher = productDir + pathSep + "bin" + pathSep + "gfxd.bat";
    }
    else {
      utilLauncher = productDir + pathSep + "bin" + pathSep + "gfxd";
    }
    final String[] startOps = new String[] { utilLauncher, "server", "start",
        "-dir=" + workingDir, "-run-netserver=false",
        "-log-file=./" + getTestName() + "-server.log", "-mcast-port=0" };
    final Process serverProc = new ProcessBuilder(startOps).start();
    final int serverProcId;
    try {
      String output = getProcessOutput(serverProc, 0, 120000, null);
      // get the processId from the output
      Matcher mt = Pattern.compile("pid:\\s*([0-9]*)\\s*status:\\s*(\\w*)",
          Pattern.MULTILINE).matcher(output);
      assertTrue("Got output: " + output, mt.find());
      assertEquals(2, mt.groupCount());
      serverProcId = Integer.valueOf(output.substring(mt.start(1), mt.end(1)));
      assertTrue(output.substring(mt.start(2)).startsWith("running"));
      assertTrue(nc.isProcessActive(serverProcId));
      // check the status
      checkServerStatus(utilLauncher, workingDir, "running");

      // kill the process
      assertTrue(nc.killProcess(serverProcId));
      while (nc.isProcessActive(serverProcId)) {
        Thread.sleep(200);
      }
      // check that status shows stopped
      checkServerStatus(utilLauncher, workingDir, "stopped");
    } finally {
      waitForProcess(new ProcessBuilder(utilLauncher, "server", "stop", "-dir="
          + workingDir).start(), 120000);
    }
    // check that status shows stopped
    checkServerStatus(utilLauncher, workingDir, "stopped");
    assertFalse(nc.isProcessActive(serverProcId));

    // getenv/setenv
    assertEquals(productDir, System.getenv("GEMFIREXD"));
    assertEquals(productDir, nc.getEnvironment("GEMFIREXD"));
    // set a new environment variable
    nc.setEnvironment("GEMFIREXD_TEST", productDir);
    assertEquals(productDir, System.getenv("GEMFIREXD_TEST"));
    assertEquals(productDir, nc.getEnvironment("GEMFIREXD_TEST"));
    // clear the new environment variable
    nc.setEnvironment("GEMFIREXD_TEST", null);
    assertNull(System.getenv("GEMFIREXD_TEST"));
    assertNull(nc.getEnvironment("GEMFIREXD_TEST"));
    // also try clearing an existing one
    nc.setEnvironment("GEMFIREXD", null);
    assertNull(System.getenv("GEMFIREXD"));
    assertNull(nc.getEnvironment("GEMFIREXD"));
    // restore it back
    nc.setEnvironment("GEMFIREXD", productDir);
    assertEquals(productDir, System.getenv("GEMFIREXD"));
    assertEquals(productDir, nc.getEnvironment("GEMFIREXD"));
  }

  // bug #50257
  public void testGfxdServerLauncherStartupOptions() throws Exception {

    // check that snappydata-store-tools.jar and snappydata-store-client.jar
    // should not be too large (indicates that snappydata-store-core.jar
    //   components are getting pulled in)
    final URI clientJarFile = ClientDriver.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI();
    final File clientJar = new File(clientJarFile);
    assertTrue("client jar " + clientJarFile + " does not exist",
        clientJar.exists());
    assertTrue("unexpected client jar length=" + clientJar.length(),
        clientJar.length() < 6000000);
    final URI toolsJarFile = GfxdServerLauncher.class.getProtectionDomain()
        .getCodeSource().getLocation().toURI();
    final File toolsJar = new File(toolsJarFile);
    assertTrue("tools jar " + toolsJarFile + " does not exist",
        toolsJar.exists());
    assertTrue("unexpected tools jar length=" + toolsJar.length(),
        toolsJar.length() < 1000000);

    final DirFile file = new DirFile("utilLauncher");
    file.deleteAll();
    assertTrue("failed to create directory " + file.getAbsolutePath(),
        file.mkdir());
    file.deleteOnExit();
    CacheServerLauncher.DONT_EXIT_AFTER_LAUNCH = true;

    final String workingdir = file.getAbsolutePath();

    final String[] startOps = new String[] { "-dir=" + workingdir,
        "-run-netserver=false",
        "-log-file=./" + getTestName() + "-utilLauncher.log", "-mcast-port=0",
        "-heap-size=512m" };
    final String[] cmd = new String[startOps.length + 2];
    cmd[0] = "server";
    cmd[1] = "start";
    for (int i = 2; i < startOps.length; i++) {
      cmd[i] = startOps[i - 2];
    }
    try {

      final GfxdServerLauncher l = new GfxdServerLauncher(
          "Fabric Server Test Instance");
      final Map<String, Object> m = l.getStartOptions(startOps);

      @SuppressWarnings("unchecked")
      final List<String> vmargs = (List<String>)m.get("vmArgs");
      assertTrue("expected UseParNewGC definition in " + vmargs,
          vmargs.contains("-XX:+UseParNewGC"));
      assertTrue("expected UseConcMarkSweepGC definition in " + vmargs,
          vmargs.contains("-XX:+UseConcMarkSweepGC"));
      assertTrue("expected CMSInitiatingOccupancyFraction definition in " + vmargs,
          vmargs.contains("-XX:CMSInitiatingOccupancyFraction=50"));

      GfxdUtilLauncher.main(cmd);
    } finally {
      try {
        GfxdUtilLauncher.main(new String[] { "server", "stop",
            "-dir=" + workingdir });
      } finally {
        file.deleteAll();
      }
    }
  }

  /** test launching the VM from the gfxd script */
  public void testGfxdServerScriptStartupOptions() throws Exception {

    final DirFile file = new DirFile("utilLauncher");
    file.deleteAll();
    assertTrue("failed to create directory " + file.getAbsolutePath(),
        file.mkdir());
    file.deleteOnExit();

    final String workingdir = file.getAbsolutePath();

    final String productDir = System.getProperty("GEMFIREXD");
    String launcher = productDir + "/bin/gfxd";
    if (System.getProperty("os.name").startsWith("Windows")) {
      launcher += ".bat";
    }
    final int mcastPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS);
    final int port = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);
    try {

      final File launcherFile = new File(launcher);
      if (!launcherFile.exists()) {
        throw new Exception("launcher " + launcher + " does not exist");
      }
      final String[] cmdOps = new String[] { launcher, "server", "start",
          "-dir=" + workingdir, "-client-bind-address=0.0.0.0",
          "-client-port=" + port, "-bind-address=localhost",
          "-log-file=./" + getTestName() + "-utilLauncher.log",
          "-log-level=config",
          "-mcast-port=" + mcastPort, "-heap-size=512m" };
      getLogger().info("launching start with " + Arrays.toString(cmdOps));
      // start the JVM process and try to connect using a client
      final Process proc = Runtime.getRuntime().exec(cmdOps, null, file);
      assertEquals(0, waitForProcess(proc, 120000));

      // now fire some queries from a client connection
      Connection conn;
      // try a few times in case of disconnect exception
      int tries = 1;
      for (;;) {
        try {
          conn = getNetConnection(port, null, null);
          break;
        } catch (SQLException sqle) {
          if (tries++ < 30 && ("08006".equals(sqle.getSQLState()) ||
              "08001".equals(sqle.getSQLState()))) {
            Thread.sleep(200);
            continue;
          }
          else {
            throw sqle;
          }
        }
      }
      final ResultSet rs = conn.createStatement().executeQuery(
          "select id from sys.members");
      assertTrue(rs.next());
      assertTrue("member ID " + rs.getString(1)
          + " does not contain localhost",
          rs.getString(1).contains("localhost"));
      assertFalse(rs.next());
      conn.close();
    } finally {
      // stop the JVM
      final String[] cmdOps = new String[] { launcher, "server", "stop",
          "-dir=" + workingdir };
      getLogger().info("launching stop with " + Arrays.toString(cmdOps));
      final Process proc = Runtime.getRuntime().exec(cmdOps, null, file);
      assertEquals(0, waitForProcess(proc, 120000));
      file.deleteAll();
    }
  }

  public void testPropertiesFile() throws Exception {

    Properties outProps = new Properties();
    File f = createPropertyFile(outProps);

    System.setProperty(com.pivotal.gemfirexd.Property.PROPERTIES_FILE, PROP_FILE_NAME);
    try {
      Class.forName("com.pivotal.gemfirexd.jdbc.EmbeddedDriver");

      jdbcConn = DriverManager.getConnection(getProtocol());

      jdbcConn.createStatement().execute("create table testtable1 ( t int)");

      validateProperties(outProps);

    } finally {
      f.delete();
      try {
        if (jdbcConn != null) {
          shutDown();
        }
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }
  } // end of -Dgemfirexd.properties=xxx check.
  
  public void testPropertiesFileAbsolutePath() throws Exception {

    Properties outProps = new Properties();
    createPropertyFile(outProps);
    
    File f = new File(".");
    
    System.setProperty(com.pivotal.gemfirexd.Property.PROPERTIES_FILE, f.getAbsolutePath()
        + File.separatorChar + PROP_FILE_NAME);
    try {
      FabricServer fab = FabricServiceManager.getFabricServerInstance();

      fab.start(null);
      
      jdbcConn = TestUtil.getConnection();

      jdbcConn.createStatement().execute("create table testtable1 ( t int)");

      validateProperties(outProps);

    } finally {
      try {
        if (jdbcConn != null) {
          shutDown();
        }
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }
  } // end of -Dgemfirexd.properties=xxx check.

  /** this feature of default props $HOME/gemfirexd.properties will be removed */
  public void DISABLED_testPropertiesFileLocationPreference() throws Exception {

    Properties outProps = new Properties();

    // no accidental left out of the properties file.
    File checkNoFile = new File(".", com.pivotal.gemfirexd.Property.PROPERTIES_FILE);
    assertFalse(checkNoFile.exists());
    
    File f = new File(System.getProperty("user.home") + File.separatorChar +  com.pivotal.gemfirexd.Property.PROPERTIES_FILE);
    getLogger().info("creating file in " + f.getAbsolutePath());
    f.createNewFile();
    
    createPropertyFile(outProps, f, null);
    
    try {
      FabricServer fab = FabricServiceManager.getFabricServerInstance();

      fab.start(null);
      
      jdbcConn = TestUtil.getConnection();

      jdbcConn.createStatement().execute("create table testtable1 ( t int)");

      validateProperties(outProps);

    } finally {
      f.delete();
      try {
        if (jdbcConn != null) {
          shutDown();
        }
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }
    
    assertFalse(f.exists());

    File wrongf = new File(System.getProperty("user.home") + File.separatorChar + com.pivotal.gemfirexd.Property.PROPERTIES_FILE);
    wrongf.createNewFile();
    
    Properties distort = new Properties();
    String locatoradd = "localhost["
        + String.valueOf(AvailablePort
            .getRandomAvailablePort(AvailablePort.SOCKET)) + "]";
    distort.setProperty(DistributionConfig.MCAST_PORT_NAME, String
        .valueOf(AvailablePort.getRandomAvailablePort(AvailablePort.JGROUPS)));
    distort.setProperty(DistributionConfig.LOCATORS_NAME, locatoradd);
    
    createPropertyFile(new Properties(), wrongf, distort);
    
    System.setProperty(Property.SYSTEM_HOME_PROPERTY, System
        .getProperty("java.io.tmpdir"));
    
    f = new File(System.getProperty(Property.SYSTEM_HOME_PROPERTY) + File.separatorChar + com.pivotal.gemfirexd.Property.PROPERTIES_FILE);
    f.createNewFile();
    
    createPropertyFile(outProps, f, null);
    
    try {
      FabricServer fab = FabricServiceManager.getFabricServerInstance();

      fab.start(null);
      
      jdbcConn = TestUtil.getConnection();

      jdbcConn.createStatement().execute("create table testtable1 ( t int)");

      validateProperties(outProps);

    } finally {
      f.delete();
      wrongf.delete();
      try {
        if (jdbcConn != null) {
          shutDown();
        }
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }
  } // end of -Dgemfirexd.properties=xxx check.
  
  public void testLocalPropertiesFileLocationPreference() throws Exception {

    Properties outProps = new Properties();

    // no accidental left out of the properties file.
    File checkLocalFile = new File(".",
        com.pivotal.gemfirexd.Property.PROPERTIES_FILE);
    assertFalse(checkLocalFile.exists());

    File f = new File(".", com.pivotal.gemfirexd.Property.PROPERTIES_FILE);
    try {
      f.createNewFile();
    }
    catch(IOException ioe) {
      getLogger().error("File couldn't be created for " + f, ioe);
      throw ioe;
    }
    
    createPropertyFile(outProps, f, null);

    try {
      FabricServer fab = FabricServiceManager.getFabricServerInstance();

      fab.start(null);

      jdbcConn = TestUtil.getConnection();

      jdbcConn.createStatement().execute("create table testtable1 ( t int)");

      validateProperties(outProps);

    } finally {
      f.delete();
      try {
        if (jdbcConn != null) {
          shutDown();
        }
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }
    assertFalse(f.exists());
  } // end of -Dgemfirexd.properties=xxx check.

  public void testConnectionPropOverridingPropertiesFile() throws Exception {

    Properties outProps = new Properties();
    File f = createPropertyFile(outProps);

    System.setProperty(com.pivotal.gemfirexd.Property.PROPERTIES_FILE, PROP_FILE_NAME);

    Properties connectionProps = new Properties();
    connectionProps.setProperty(DistributionConfig.CONSERVE_SOCKETS_NAME,
        "true");
    connectionProps.setProperty(DistributionConfig.ENABLE_TIME_STATISTICS_NAME,
        "false");
    connectionProps.setProperty(
        DistributionConfig.STATISTIC_SAMPLING_ENABLED_NAME, "false");
    connectionProps.setProperty(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
        getTestName() + ".connection_override.gfs");
    connectionProps.setProperty(Attribute.LOG_FILE,
        getTestName() + ".connection_override.log");
    try {
      loadDriver();

      jdbcConn = DriverManager.getConnection(getProtocol(), connectionProps);

      jdbcConn.createStatement().execute("create table testtable1 ( t int)");

      outProps.putAll(connectionProps);
      validateProperties(outProps);

    } finally {
      try {
        f.delete();
        if (jdbcConn != null) {
          shutDown();
        }
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }

  } // end of connection props overriding property file

  public void testSystemPropOverridingPropertiesFile() throws Exception {

    Properties outProps = new Properties();
    File f = createPropertyFile(outProps);

    System.setProperty(com.pivotal.gemfirexd.Property.PROPERTIES_FILE, PROP_FILE_NAME);

    System.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.CONSERVE_SOCKETS_NAME, "true");
    System.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.ENABLE_TIME_STATISTICS_NAME, "false");
    System.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.STATISTIC_SAMPLING_ENABLED_NAME, "false");
    System.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
        getTestName() + ".system_override.gfs");
    System.setProperty(GfxdConstants.GFXD_LOG_FILE,
        getTestName() + ".system_override.log");
    try {
      loadDriver();

      jdbcConn = DriverManager.getConnection(getProtocol());

      jdbcConn.createStatement().execute("create table testtable1 ( t int)");

      for (Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
        String key = (String)e.getKey();
        String val = (String)e.getValue();
        if (key.startsWith(DistributionConfig.GEMFIRE_PREFIX)) {
          outProps.put(key
              .substring(DistributionConfig.GEMFIRE_PREFIX.length()), val);
        } else if (key.startsWith(GfxdConstants.GFXD_PREFIX)) {
          outProps.put(key.substring(GfxdConstants.GFXD_PREFIX.length()), val);
        }
      }
      validateProperties(outProps);

    } finally {
      f.delete();
      try {
        if (jdbcConn != null) {
          shutDown();
        }
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }

  } // end of system props overriding property file.

  public void testSystemPropOverridingConnPropOverridingPropertiesFile()
      throws Exception {

    Properties outProps = new Properties();
    File f = createPropertyFile(outProps);

    System.setProperty(com.pivotal.gemfirexd.Property.PROPERTIES_FILE, PROP_FILE_NAME);

    System.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.CONSERVE_SOCKETS_NAME, "true");
    System.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.ENABLE_TIME_STATISTICS_NAME, "false");
    System.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.STATISTIC_SAMPLING_ENABLED_NAME, "false");
    System.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
        getTestName() + ".system_override.gfs");
    System.setProperty(GfxdConstants.GFXD_LOG_FILE,
        getTestName() + ".system_override.log");

    Properties connectionProps = new Properties();
    connectionProps.setProperty(DistributionConfig.ENABLE_TIME_STATISTICS_NAME,
        "false");
    connectionProps.setProperty(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
        getTestName() + ".connection_override.gfs");
    connectionProps.setProperty(Attribute.LOG_FILE,
        getTestName() + ".connection_override.log");
    try {
      loadDriver();

      jdbcConn = DriverManager.getConnection(getProtocol(), connectionProps);

      jdbcConn.createStatement().execute("create table testtable1 ( t int)");

      outProps.putAll(connectionProps);

      for (Map.Entry<Object, Object> e : System.getProperties().entrySet()) {
        String key = (String)e.getKey();
        String val = (String)e.getValue();
        if (key.startsWith(DistributionConfig.GEMFIRE_PREFIX)) {
          outProps.put(key
              .substring(DistributionConfig.GEMFIRE_PREFIX.length()), val);
        } else if (key.startsWith(GfxdConstants.GFXD_PREFIX)) {
          outProps.put(key.substring(GfxdConstants.GFXD_PREFIX.length()), val);
        }
      }
      validateProperties(outProps);

    } finally {
      f.delete();
      try {
        if (jdbcConn != null) {
          shutDown();
        }
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }

  } // end of system properties overriding gemfirexd.properties file.

  public void testNetworkStartupAPI() throws Exception {
    Properties outProps = new Properties();
    File f = createPropertyFile(outProps);

    System.setProperty(com.pivotal.gemfirexd.Property.PROPERTIES_FILE, PROP_FILE_NAME);

    int port, port2;
    while ((port = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET))
        <= FabricService.NETSERVER_DEFAULT_PORT);

    final FabricServer fabapi = FabricServiceManager.getFabricServerInstance();
    try {
      fabapi.start(null);

      final NetworkInterface ni = fabapi.startDRDAServer(null,
          port, null);

      while ((port2 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET))
          <= FabricService.NETSERVER_DEFAULT_PORT);
      final NetworkInterface ni2 = fabapi.startThriftServer(null,
          port2, null);
      try {
        ni.logConnections(true);
        ni.setMaxThreads(20);
        ni.setTimeSlice(10);
        ni.trace(true);

        int maxT = ni.getMaxThreads();
        assertEquals(20, maxT);
        int timSl = ni.getTimeSlice();
        if (ClientSharedUtils.isThriftDefault()) {
          assertEquals(-1, timSl);
        } else {
          assertEquals(10, timSl);
        }
        ni.trace(false);

        // expect a specific exception as connection number 2 shouldn't exist.
        try {
          ni.trace(2, true);
        } catch (Exception expect) {
          if (!expect.getMessage().equals("java.lang.Exception: Session, 2, "
                  + "not found or other error occurred starting tracing. "
                  + "Check runtimeinfo for valid session numbers and "
                  + "gemfirexd log for possible permission errors or "
                  + "IO errors accessing the trace file.")) {
            getLogger().error("got exception " + expect.getMessage(), expect);
          }
        }

        Properties curProps = ni.getCurrentProperties();
        StringBuilder sb = new StringBuilder("# --- Current Properties ---");
        sb.append(SanityManager.lineSeparator);

        for (Map.Entry<Object, Object> e : curProps.entrySet()) {
          sb.append((String)e.getKey());
          sb.append("=");
          sb.append((String)e.getValue());
          sb.append(SanityManager.lineSeparator);
        }
        getLogger().info(sb.toString());

        getLogger().info("sysInfo = " + ni.getSysinfo());
        getLogger().info("runtimeInfo = " + ni.getRuntimeInfo());

        final InetAddress localHost = InetAddress.getByName(null);
        // NOTE: creating the 13th connection and setting trace on it. if any
        // call gets added above, connNum might have to be changed in below
        // trace(...) call.
        final String drdaPrefix = ClientSharedUtils.isThriftDefault()
            ? "jdbc:gemfirexd:drda://" : "jdbc:gemfirexd://";
        final String thriftPrefix = ClientSharedUtils.isThriftDefault()
            ? "jdbc:gemfirexd://" : "jdbc:gemfirexd:thrift://";
        final String host = localHost.getHostName();
        final String netUrl = drdaPrefix + host + '[' + port + "]/";
        final String netUrl2 = thriftPrefix + host + '[' + port2 + "]/";

        loadNetDriver();
        Connection conn = DriverManager.getConnection(netUrl);
        Connection conn2 = DriverManager.getConnection(netUrl2);

        ni.trace(13, true);

        conn.createStatement().execute("create table testtable1 (v int)");

        // check empty statement text
        try {
          conn.createStatement().execute("");
          fail("expected syntax error");
        } catch (SQLException sqle) {
          if (!"42X01".equals(sqle.getSQLState())) {
            throw sqle;
          }
        }
        try {
          conn2.createStatement().execute("");
          fail("expected syntax error");
        } catch (SQLException sqle) {
          if (!"42X01".equals(sqle.getSQLState())) {
            throw sqle;
          }
        }

        conn.close();
        conn2.close();
        // end of 13th connection.

        Properties props = new Properties();
        // force connection to the system network server only
        props.setProperty("load-balance", "false");
        conn = DriverManager.getConnection(netUrl, getNetProperties(props));
        conn2 = DriverManager.getConnection(netUrl2, getNetProperties(props));
        // verify that meta-data tables can be queried successfully
        ResultSet rs = conn.createStatement().executeQuery(
            "select KIND, NETSERVERS, THRIFTSERVERS from SYS.MEMBERS");
        final String fullHost = getFullHost(localHost);
        assertTrue("expected one row in meta-data query", rs.next());
        assertEquals("datastore(normal)", rs.getString(1));
        assertEquals(fullHost + '[' + port2 + "]," + fullHost +
            '[' + port + ']', rs.getString(2));
        assertEquals(fullHost + '[' + port2 + ']',
            rs.getString(3));
        assertFalse("expected no more than one row from SYS.MEMBERS", rs.next());

        rs = conn2.createStatement().executeQuery(
            "select KIND, NETSERVERS, THRIFTSERVERS from SYS.MEMBERS");
        assertTrue("expected one row in meta-data query", rs.next());
        assertEquals("datastore(normal)", rs.getString(1));
        assertEquals(fullHost + '[' + port2 + "]," + fullHost +
            '[' + port + ']', rs.getString(2));
        assertEquals(fullHost + '[' + port2 + ']',
            rs.getString(3));
        assertFalse("expected no more than one row from SYS.MEMBERS", rs.next());

        conn.close();
        conn2.close();
      } finally {
        ni.stop();
        ni2.stop();
      }
    } finally {
      f.delete();
      try {
        fabapi.stop(null);
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }
  }

  public void testLocatorStartupAPI() throws Exception {
    final InetAddress localHost = InetAddress.getByName("localhost");
    int port, netPort;
    Properties props = doCommonSetup(null);

    // first check with explicit bind-address
    FabricService fabapi = FabricServiceManager.getFabricLocatorInstance();
    port = startLocator(localHost.getHostAddress(), -1, props);
    try {
      // verify that no tables can be created in this VM
      final Connection conn = getConnection();
      final Statement stmt = conn.createStatement();
      addExpectedException(GemFireXDRuntimeException.class);
      try {
        stmt.execute("create table testapi(id int)");
        fail("expected DDL execution to fail");
      } catch (SQLException ex) {
        if (!"38000".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      removeExpectedException(GemFireXDRuntimeException.class);
      // verify that meta-data tables can be queried successfully
      final ResultSet rs = stmt
          .executeQuery("select KIND, LOCATOR from SYS.MEMBERS");
      assertTrue("expected one row in meta-data query", rs.next());
      assertEquals("locator(normal)", rs.getString(1));
      assertEquals(localHost.getHostAddress() + '[' + port + ']', rs
          .getString(2));
      assertFalse("expected no more than one row from SYS.MEMBERS", rs.next());
    } finally {
      try {
        fabapi.stop(null);
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }

    // next check with a specified non-localhost bind-address
    fabapi = FabricServiceManager.getFabricLocatorInstance();
    port = startLocator(localHost.getHostName(), -1, props);
    try {
      // verify that no tables can be created in this VM
      final Connection conn = getConnection();
      final Statement stmt = conn.createStatement();
      addExpectedException(GemFireXDRuntimeException.class);
      try {
        stmt.execute("create table testapi(id int)");
        fail("expected DDL execution to fail");
      } catch (SQLException ex) {
        if (!"38000".equals(ex.getSQLState())) {
          throw ex;
        }
      }
      removeExpectedException(GemFireXDRuntimeException.class);
      // verify that meta-data tables can be queried successfully
      final ResultSet rs = stmt
          .executeQuery("select KIND, LOCATOR from SYS.MEMBERS");
      assertTrue("expected one row in meta-data query", rs.next());
      assertEquals("locator(normal)", rs.getString(1));
      assertEquals("127.0.0.1[" + port + ']', rs.getString(2));
      assertFalse("expected no more than one row from SYS.MEMBERS", rs.next());
    } finally {
      try {
        fabapi.stop(null);
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }

    // check with a server with GFE locator and a network server using the JDBC
    // client connection
    fabapi = FabricServiceManager.getFabricServerInstance();
    netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    props = doCommonSetup(null);
    ((FabricServer)fabapi).start(props);
    fabapi.startNetworkServer("0.0.0.0", netPort, props);
    InternalLocator gfeLocator = null;
    try {
      // also start a GFE locator and check that it is available too
      int port2 = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
      final InternalDistributedSystem sys = InternalDistributedSystem
          .getConnectedInstance();
      gfeLocator = InternalLocator.startLocator(port2, null, null, sys
          .getLogWriterI18n(), sys.getSecurityLogWriter()
          .convertToLogWriterI18n(), localHost, false, null, true, true, null);
      // we should not be able to connect to server
      final Connection conn = getNetConnection(netPort, null, null);
      final Statement stmt = conn.createStatement();
      stmt.execute("create table testapi(id int)");
      stmt.execute("drop table testapi");
      // verify that meta-data tables can be queried successfully
      final ResultSet rs = stmt
          .executeQuery("select KIND, LOCATOR, NETSERVERS from SYS.MEMBERS");
      assertTrue("expected one row in meta-data query", rs.next());
      assertEquals("loner", rs.getString(1));
      if (!rs.getString(2).equals(
          localHost.getHostAddress() + '[' + port2 + ']')) {
        assertEquals(getFullHost(localHost) + '[' + port2 + ']',
            rs.getString(2));
      }
      String netStr = rs.getString(3);
      assertTrue("Unexpected network server address " + netStr,
          netStr.endsWith("/0.0.0.0[" + netPort + ']'));
      assertFalse("expected no more than one row from SYS.MEMBERS", rs.next());
    } finally {
      try {
        if (gfeLocator != null) {
          gfeLocator.stop();
        }
        fabapi.stop(null);
      } finally {
        DistributedSQLTestBase.deleteStrayDataDictionaryDir();
      }
    }
  }

  public void testLocatorStopProblem() throws Exception {
    // keystore creation: keytool -genkey -alias self -dname "CN=trusted"
    // -validity 3650 -keypass password -keystore ./trusted.keystore -storepass
    // password -storetype JKS
    final String testsDir = TestUtil.getResourcesDir();
    System.setProperty("javax.net.debug","ssl");

    Properties startProps = doCommonSetup(null);
    startProps.remove("locators");
    //startProps.put("ssl-enabled", "true");
    startProps.put("mcast-port", "0");
    startProps.put("log-level", "fine");
    startProps.put("security-log-level", "fine");
    //startProps.put("gemfirexd.authentication.provider", "BUILTIN");
    //startProps.put("gemfirexd.authentication.required", "true");
    startProps.put("auth-provider", "BUILTIN");
    startProps.put("gemfirexd.user.test", "test123");
    startProps.put("user", "test");
    startProps.put("password", "test123");
    System.setProperty("javax.net.ssl.keyStore", testsDir
        + "/lib/trusted.keystore");
    System.setProperty("javax.net.ssl.keyStorePassword", "password");
    System.setProperty("javax.net.ssl.trustStore", testsDir
        + "/lib/trusted.keystore");
    System.setProperty("javax.net.ssl.trustStorePassword", "password");

    Properties stopProps = new Properties();
    stopProps.put("user", "test");
    stopProps.put("password", "test123");
    int netPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    runLocatorRestartWithAuth(startProps, stopProps, netPort);

    // try to start the locator again
    // use same netPort to confirm that old NetworkServer is no longer running
    runLocatorRestartWithAuth(startProps, stopProps, netPort);
  }

  private void runLocatorRestartWithAuth(final Properties startProps,
      final Properties stopProps, final int netPort) throws Exception {
    stopProps.put("user", "test");
    stopProps.put("password", "test123");

    int locPort = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    FabricLocator locator = FabricServiceManager.getFabricLocatorInstance();
    locator.start("localhost", locPort, startProps);

    Properties socketProps = new Properties();
    socketProps.put("gemfirexd.drda.sslMode", "peerAuthentication");
    NetworkInterface ni = locator.startNetworkServer("localhost", netPort,
        socketProps);
    getLogger().info("GemFire XD started. State: " + locator.status());

    // first try stopping via the interface
    ni.stop();
    getLogger().info("GemFire XD stopped. State: " + locator.status());
    // wait for OS to release the socket
    Thread.sleep(3000);

    locator.startNetworkServer("localhost", netPort, socketProps);
    getLogger().info("GemFire XD started. State: " + locator.status());

    if (locator.status() == FabricService.State.RUNNING) {
      stopProps.put("stop-netservers", "true");
      locator.stop(stopProps);
      getLogger().info("GemFire XD stopped. State: " + locator.status());
    }
  }

  public void testConnectionWithAuthentication() throws SQLException,
      InterruptedException {
    final FabricServer fabapi = FabricServiceManager.getFabricServerInstance();
    final String currentHost = DistributedTestBase.getIPLiteral();

    final Properties shutdownProp = new Properties();
    shutdownProp.setProperty("user", "sysUser1");
    shutdownProp.setProperty("password", "pwd_sysUser1");
    
     Properties sysprop;

     int locatorPort = AvailablePort
          .getRandomAvailablePort(AvailablePort.SOCKET);

     sysprop = doCommonSetup(null);
     sysprop = doSecuritySetup(sysprop);
     
     sysprop.setProperty("log-level", "fine");
     sysprop.setProperty(DistributionConfig.START_LOCATOR_NAME, currentHost
        + "[" + String.valueOf(locatorPort) + "]");
     sysprop.setProperty(DistributionConfig.LOCATORS_NAME, currentHost + "["
        + String.valueOf(locatorPort) + "]");
     
     fabapi.start(sysprop);
   
      try {
        addExpectedException(SQLException.class);
        try {
          TestUtil.getConnection();
          fail("Security exception expected ... ");
        } catch (SQLException sqle) {
          if (!"08004".equals(sqle.getSQLState())) { // LOGIN_FAILED
            throw GemFireXDRuntimeException.newRuntimeException(
                "unexpected exception ", sqle);
          }
        }
        
        Properties sysProp = new Properties();
        sysProp.setProperty("user", "sysUser1");
        sysProp.setProperty("password", "pwd_sysUser1");
        Connection conn = TestUtil.getConnection(sysProp);
  
        CallableStatement cs = conn
            .prepareCall("CALL sys.create_user('firstDBUser', 'conectme')");
        // should pass without exception
        cs.execute();
        conn.createStatement().execute("CALL sys.drop_user('firstDBUser')");
        cs = conn.prepareCall("CALL sys.create_user('"
            + Property.USER_PROPERTY_PREFIX + "sysUser1', 'raise_28503')");
        try {
          cs.execute();
          fail("shouldn't have passed without exception ..");
        } catch(SQLException sqle) {
          if(!"28503".equals(sqle.getSQLState())) {
            throw GemFireXDRuntimeException.newRuntimeException(
                "unexpected exception with state " + sqle.getSQLState(), sqle);
          }
        }
        conn.close();

        Properties p = new Properties();
        p.put(Attribute.TX_SYNC_COMMITS, "true");
        conn = DriverManager.getConnection(TestUtil.getProtocol()
            + ";user=sysUser1;password=pwd_sysUser1;", p);

        conn.createStatement().execute("create schema test_schema authorization firstDBUser ");
        
        CallableStatement cs1 = conn.prepareCall("CALL sys.create_user('"
            + Property.USER_PROPERTY_PREFIX + "firstDBUser', 'conectme__X')");
        cs1.execute();
        
        try {
          CallableStatement cs3 = conn.prepareCall("CALL sys.create_user('"
              + Property.USER_PROPERTY_PREFIX + "firstDBUser', 'conectme')");
          cs3.execute();
          fail("Expected user already defined exception.");
        } catch(SQLException sqle) {
          if (!"28504".equals(sqle.getSQLState())) {
            fail("Expected user already defined exception", sqle);
          }
        }

        // change_password of only self with authentication of old password.
  
        // create_user & multiple change_password conflation
  
        // use create_user to boot a VM and bypass authorization
  
        // restrict drop_user if implicit schema exists.
        
        CallableStatement cs2 = conn.prepareCall("CALL sys.change_password('"
            + Property.USER_PROPERTY_PREFIX + "firstDBUser', '', 'conectme')");
        cs2.execute();

        conn.close();

        conn = DriverManager.getConnection(TestUtil.getProtocol()
            + ";user=firstDBUser;password=conectme;", p);
        //conn.setAutoCommit(false);

        try {
          conn.createStatement().execute("drop table test_schema.checkTab");
        } catch (SQLException se) {
          if (!"42Y55".equals(se.getSQLState())) {
             throw se;
          }
        }
        conn.createStatement().execute(
            "create table test_schema.checkTab "
                + "( id int, cdesc varchar(10) )");

        conn.close();

      } finally {
        removeExpectedException(SQLException.class);
        try {
          fabapi.stop(shutdownProp);
        }
        catch(Exception ignore) {
          getLogger().warn(
              "Exception occurred while shutting down with credential ", ignore);
        }
      }
  }

  public void testGatewayReceiverHNSCommandLineOptions() throws Exception {
    final DirFile file = new DirFile("utilLauncher");
    file.deleteAll();
    assertTrue("failed to create directory " + file.getAbsolutePath(),
        file.mkdir());
    file.deleteOnExit();

    final String workingdir = file.getAbsolutePath();

    final String productDir = System.getProperty("GEMFIREXD");
    String launcher = productDir + "/bin/gfxd";
    if (System.getProperty("os.name").startsWith("Windows")) {
      launcher += ".bat";
    }
    final int mcastPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.JGROUPS);
    final int port = AvailablePort.getRandomAvailablePort(AvailablePort.SOCKET);
    try {

      final File launcherFile = new File(launcher);
      if (!launcherFile.exists()) {
        throw new Exception("launcher " + launcher + " does not exist");
      }
      final String[] cmdOps = new String[] { launcher, "server", "start",
          "-dir=" + workingdir, "-client-bind-address=0.0.0.0",
          "-client-port=" + port,
          "-log-file=./" + getTestName() + "-utilLauncher.log",
          "-log-level=config", "-mcast-port=" + mcastPort, "-heap-size=512m",
          "-custom-NICJVM=NIC1" };
      getLogger().info("launching start with " + Arrays.toString(cmdOps));
      // start the JVM process and try to connect using a client
      final Process proc = Runtime.getRuntime().exec(cmdOps, null, file);
      assertEquals(0, waitForProcess(proc, 120000));

      Connection conn;
      // try a few times in case of disconnect exception
      int tries = 1;
      for (;;) {
        try {
          conn = getNetConnection(port, null, null);
          break;
        } catch (SQLException sqle) {
          if (tries++ < 30 && ("08006".equals(sqle.getSQLState()) ||
              "08001".equals(sqle.getSQLState()))) {
            Thread.sleep(200);
            continue;
          }
          else {
            throw sqle;
          }
        }
      }

      conn.createStatement().executeUpdate(
          "create gatewayreceiver ok (bindaddress 'localhost' hostnameforsenders 'NICJVM');");

      final ResultSet rs1 = conn.createStatement().executeQuery(
          "select * from SYS.GATEWAYRECEIVERS");
      assertTrue(rs1.next());
      
      assertEquals("NIC1",rs1.getString(9));
      assertFalse(rs1.next());
      rs1.close();      
      conn.close();
    } finally {
      // stop the JVM
      final String[] cmdOps = new String[] { launcher, "server", "stop",
          "-dir=" + workingdir };
      getLogger().info("launching stop with " + Arrays.toString(cmdOps));
      final Process proc = Runtime.getRuntime().exec(cmdOps, null, file);
      assertEquals(0, waitForProcess(proc, 120000));
      file.deleteAll();
    }
  }

  private Properties doSecuritySetup(Properties props) {
    
    props.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    
    props.setProperty(DistributionConfig.GEMFIRE_PREFIX
        + DistributionConfig.SECURITY_LOG_LEVEL_NAME, "finest");

    props.setProperty(Monitor.DEBUG_TRUE, GfxdConstants.TRACE_AUTHENTICATION
        + "," + GfxdConstants.TRACE_SYS_PROCEDURES + ","
        + GfxdConstants.TRACE_FABRIC_SERVICE_BOOT);

    props.setProperty(Property.REQUIRE_AUTHENTICATION_PARAMETER, Boolean
        .toString(true));
    
    props.setProperty(com.pivotal.gemfirexd.Property.SQL_AUTHORIZATION, Boolean
        .toString(true));
    
    props.setProperty(Property.USER_PROPERTY_PREFIX + "sysUser1", "pwd_sysUser1");
    props.setProperty(PartitionedRegion.rand.nextBoolean()
        ? com.pivotal.gemfirexd.Attribute.USERNAME_ATTR
        : com.pivotal.gemfirexd.Attribute.USERNAME_ALT_ATTR, "sysUser1");
    props.setProperty(com.pivotal.gemfirexd.Attribute.PASSWORD_ATTR, "pwd_sysUser1");

    props.setProperty(Property.GFXD_AUTH_PROVIDER,
        com.pivotal.gemfirexd.Constants.AUTHENTICATION_PROVIDER_BUILTIN);
    
    return props;
  }

  private File createPropertyFile(Properties outProps) throws IOException {
    final File file = new File(PROP_FILE_NAME);
    file.createNewFile();
    createPropertyFile(outProps, file, null);
    return file;
  }

  private void createPropertyFile(Properties outProps, File file, Properties wrongData)
      throws IOException {

    assertTrue(file.canWrite());

    String locatoradd = "localhost["
        + String.valueOf(AvailablePort
            .getRandomAvailablePort(AvailablePort.SOCKET)) + "]";

    getLogger().info(
        "created writable file " + file.getAbsoluteFile() + " for "
            + TestUtil.currentTest + " with locator address " + locatoradd);

    FileOutputStream fs = new FileOutputStream(file);

    // gfe props to be read and passed on in DS.connect. so 'gemfire.' prefix is
    // not necessary

    outProps.setProperty("gemfirexd.host-data", "true");
    outProps.setProperty(DistributionConfig.MCAST_PORT_NAME, "0");
    outProps.setProperty(DistributionConfig.BIND_ADDRESS_NAME, "localhost");
    outProps.setProperty(DistributionConfig.LOG_LEVEL_NAME, "config");
    outProps.setProperty(DistributionConfig.CONSERVE_SOCKETS_NAME, "false");
    outProps
        .setProperty(DistributionConfig.ENABLE_TIME_STATISTICS_NAME, "true");
    outProps.setProperty(DistributionConfig.STATISTIC_SAMPLING_ENABLED_NAME,
        "true");
    outProps.setProperty(DistributionConfig.START_LOCATOR_NAME, locatoradd);
    outProps.setProperty(DistributionConfig.LOCATORS_NAME, locatoradd);

    String filename = getTestName();
    outProps.setProperty(Attribute.LOG_FILE, filename + ".log");
    outProps.setProperty(DistributionConfig.STATISTIC_ARCHIVE_FILE_NAME,
        filename + ".gfs");
    if(wrongData != null) {
      outProps.putAll(wrongData);
    }

    // save the property file
    outProps.store(fs, "-- gemfirexd default server properties ");

    fs.close();
  }

  private void validateProperties(Properties passedIn) {
    final List<String> gfeProps = Arrays.asList(AbstractDistributionConfig
        ._getAttNames());
    Properties connectedProps = InternalDistributedSystem
        .getConnectedInstance().getProperties();

    for (Map.Entry<Object, Object> e : passedIn.entrySet()) {
      String key = (String)e.getKey();
      if (!gfeProps.contains(key)) {
        // check host-data separately
        if (key.equals("gemfirexd.host-data")) {
          boolean isStore = "true".equalsIgnoreCase((String)e.getValue());
          GemFireStore.VMKind kind = Misc.getMemStore().getMyVMKind();
          assertTrue(key + " not present",
              isStore ? kind.isStore() : kind.isAccessor());
        }
        continue;
      }

      String val = (String)e.getValue();
      assertTrue(key + " not present", connectedProps.containsKey(key));

      String connectedval = connectedProps.getProperty(key);

      // locator string repeats thrice
      if (key.contains("locators")) {
        assertTrue(key + " connected val = " + connectedval + " passed val = "
            + val, connectedval.contains(val));
      }
      else {
        assertTrue(key + " expected " + val + " found " + connectedval,
            connectedval.equals(val));
      }
    }
  }

  private String getFullHost(final InetAddress addr) {
    final String hostName = addr.getCanonicalHostName();
    if (hostName != null) {
      return hostName + '/' + addr.getHostAddress();
    }
    return '/' + addr.getHostAddress();
  }

  private void checkServerStatus(final String utilLauncher,
      final String workingDir, final String expectedStatus) throws Exception {
    final int maxWait = 30000;
    final int loopWait = 500;
    String status = "";
    for (int i = 1; i <= maxWait / loopWait; i++) {
      // check the status
      final Process serverProc = new ProcessBuilder(utilLauncher, "server",
          "status", "-dir=" + workingDir).start();
      final String output = getProcessOutput(serverProc, 0, 120000, null);
      // get the processId from the output
      final Matcher mt = Pattern
          .compile("status:\\s*(\\w*)", Pattern.MULTILINE).matcher(output);
      assertTrue("Got output: " + output, mt.find());
      assertEquals(1, mt.groupCount());
      status = output.substring(mt.start(1), mt.end(1));
      if (expectedStatus.equals(status)) {
        return;
      }
      Thread.sleep(loopWait);
    }
    assertEquals(expectedStatus, status);
  }

  private int waitForProcess(final Process p, final int maxWaitMillis)
      throws InterruptedException {
    final int loopWaitMillis = 100;
    int maxTries = maxWaitMillis / loopWaitMillis;
    while (maxTries-- > 0) {
      try {
        return p.exitValue();
      } catch (IllegalThreadStateException itse) {
        // continue in the loop
        Thread.sleep(loopWaitMillis);
      }
    }
    return -1;
  }
}
