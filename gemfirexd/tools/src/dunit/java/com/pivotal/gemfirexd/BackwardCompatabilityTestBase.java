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
import java.io.FilenameFilter;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.regex.Pattern;

import com.gemstone.gemfire.internal.AvailablePort;
import com.gemstone.gemfire.internal.shared.ClientSharedUtils;
import com.gemstone.gemfire.internal.shared.NativeCalls;
import com.pivotal.gemfirexd.BackwardCompatabilityDUnit.ClientRun;
import com.pivotal.gemfirexd.BackwardCompatabilityDUnit.ProductClient;
import com.pivotal.gemfirexd.tools.GfxdUtilLauncher;
import io.snappydata.test.dunit.Host;
import io.snappydata.test.dunit.SerializableCallable;
import io.snappydata.test.util.TestException;

/**
 * Common base methods for BackwardCompatability*DUnit tests.
 */
@SuppressWarnings("serial")
public abstract class BackwardCompatabilityTestBase extends
    DistributedSQLTestBase {

  protected String product;

  protected static final String PRODUCT_SQLFIRE = "SQLFIRE";
  protected static final String PRODUCT_GEMFIREXD = "GEMFIREXD";

  protected final String rollingUpgradeBaseVersion = "1.1.1";
  protected final String rollingUpgradeBaseVersionDir = "sqlfire/releases/SQLFire1.1.1-all";

  /**
   * Version lists that need to be compatible amongst one another. Please add to
   * these once a new patch release or minor release is done.
   */
  protected static final String[][] compatibleVersionLists = new String[][] {
  // {"1.0.2"},
  { "1.0.2", "1.0.3" },
  // { "1.0", "1.0.1", "1.0.2", "1.0.2.1", "1.0.3" },
  // add next set of versions below
  };

  /**
   * The location of the released jars for above versions in /gcm/where.
   */
  protected static final String[][] compatibleVersionProductDirs = new String[][] { {
      "sqlfire/102/all", "sqlfire/103/all" },
  // { "gemfirexd/102/all"},
  // { "gemfirexd/100/all", "gemfirexd/101/all", "gemfirexd/102/all",
  // "gemfirexd/1021/all", "gemfirexd/103/all" },
  // add directories for next set of versions below
  };

  /** 1.0.2 introduced bug #44988 */
  protected static final String bug44988AffectedVersion = "1.0.2";

  protected static final String GCM_WHERE;

  protected static final Pattern ABSOLUTE_PATH_PAT = Pattern
      .compile("^/|([a-zA-Z]:)");

  static {
    String gcmdir = System.getenv("GCMDIR");
    if (gcmdir == null || gcmdir.length() == 0) {
      gcmdir = "/gcm";
    }
    GCM_WHERE = gcmdir + "/where";
  }

  protected int currentListIdx = -1;
  protected int currentVersIdx = -1;

  protected String vm1WorkingDir;
  protected String vm2WorkingDir;
  protected String vm3WorkingDir;
  protected String vm4WorkingDir;

  SerializableCallable getVMWorkingDir = new SerializableCallable(
      "get working directory of this JVM") {
    @Override
    public Object call() {
      return getSysDirName();
    }
  };

  @Override
  public void setUp() throws Exception {
    System.setProperty("gemfirexd.thrift-default", "false");
    ClientSharedUtils.setThriftDefault(false);
    super.setUp();
    vm1WorkingDir = (String)Host.getHost(0).getVM(0).invoke(getVMWorkingDir);
    vm2WorkingDir = (String)Host.getHost(0).getVM(1).invoke(getVMWorkingDir);
    vm3WorkingDir = (String)Host.getHost(0).getVM(2).invoke(getVMWorkingDir);
    vm4WorkingDir = (String)Host.getHost(0).getVM(3).invoke(getVMWorkingDir);
  }

  @Override
  public void tearDown2() throws Exception {
    System.clearProperty("gemfirexd.thrift-default");
    ClientSharedUtils.setThriftDefault(true);
    super.tearDown2();
    final String workingDir = getSysDirName();
    if (currentListIdx >= 0 && currentVersIdx >= 0) {
      try {
        stopVersionedServer(currentListIdx, currentVersIdx, workingDir);
      } catch (Exception e) {
        // ignore any exception here
        getLogWriter().info("Ignored exception during tearDown stop " + e);
      }
    }
    // Stop all VMs
    stopAllVMs();

    String[] customStoreDirs = new String[] { vm1WorkingDir + "/dir1",
        vm2WorkingDir + "/dir1", vm3WorkingDir + "/dir1",
        vm4WorkingDir + "/dir1" };
    cleanUpCustomStoreFiles(customStoreDirs);
  }

  public BackwardCompatabilityTestBase(String name) {
    super(name);
  }

  public static final class ProcessStart {
    public final Process proc;
    public final int port;
    public final boolean isLocator;
    public final String version;
    public final String logFile;

    public ProcessStart(Process proc, int port, boolean isLocator,
        String version, String logFile) {
      this.proc = proc;
      this.logFile = logFile;
      this.isLocator = isLocator;
      this.port = port;
      this.version = version;
    }
  }

  /**
   * Wait for locators/servers encapsulated by {@link ProcessStart} to finish
   * startup.
   */
  public void waitForProcesses(ProcessStart... procStarts)
      throws InterruptedException {
    for (ProcessStart procStart : procStarts) {
      final int exitValue = procStart.proc.waitFor();
      String procType = procStart.isLocator ? "locator" : "server";
      if (procStart.version != null) {
        procType += (" version " + procStart.version);
      }
      else {
        procType = "current version " + procType;
      }
      if (exitValue != 0) {
        throw new TestException("Unexpected exit value " + exitValue
            + " while starting " + procType + ". See logs in "
            + procStart.logFile + " and start file output.");
      } else {
        getLogWriter().info("Started " + procType);
      }
    }
  }

  protected void sleepForAsyncLocatorStart(Process locProc, int locPort)
      throws InterruptedException {
    Thread.sleep(4000);

    int maxTimes = 20;
    while (maxTimes-- > 0) {
      Thread.sleep(1000);
      try {
        locProc.exitValue();
        break;
      } catch (IllegalThreadStateException itse) {
        // continue
      }
      if (!AvailablePort.isPortAvailable(locPort, AvailablePort.SOCKET)) {
        break;
      }
    }
  }

  protected void sleepForAsyncServerStart() throws InterruptedException {
    Thread.sleep(2000);
  }

  /**
   * Starts locator using this GemfireXD source base in background.
   */
  public ProcessStart startCurrentVersionLocator(String directory)
      throws Exception {
    String utilLauncher = getCurrentUtilLauncher();
    final int peerDiscoveryPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);

    getLogWriter().info(
        "Starting current version GEMFIREXD locator from " + directory
            + " on port " + peerDiscoveryPort + " launcher=" + utilLauncher);

    final String logFile = getTestLogNamePrefix() + "-locator" + "current"
        + ".log";

    String[] startOps = null;

    startOps = new String[] { utilLauncher, "locator", "start",
        "-dir=" + directory, "-run-netserver=false", "-log-file=" + logFile,
        "-heap-size=512m", "-peer-discovery-port=" + peerDiscoveryPort
    // ,"-log-level=fine"
    };

    final Process serverProc = new ProcessBuilder(startOps).start();
    sleepForAsyncLocatorStart(serverProc, peerDiscoveryPort);
    return new ProcessStart(serverProc, peerDiscoveryPort, true, null, logFile);
  }

  public void stopCurrentVersionLocator(String directory) throws Exception {
    String utilLauncher = getCurrentUtilLauncher();

    getLogWriter().info("Stopping current version GEMFIREXD locator from "
        + directory + " launcher=" + utilLauncher);

    final String[] startOps = new String[] { utilLauncher, "locator", "stop",
        "-dir=" + directory };

    Process serverProc = new ProcessBuilder(startOps).start();
    int exitValue = serverProc.waitFor();
    if (exitValue != 0) {
      getLogWriter().error(
          "Unexpected exit value " + exitValue
              + " while stopping current version locator. Trying again.");
      Thread.sleep(5);
      serverProc = new ProcessBuilder(startOps).start();
      exitValue = serverProc.waitFor();
      if (exitValue != 0) {
        getLogWriter().error(
            "Unexpected exit value " + exitValue
                + " while stopping current version locator in " + directory
                + ". Giving up.");
      }
    }
  }

  public ProcessStart startCurrentVersionServer(String directory,
      int locatorPort) throws Exception {
    String utilLauncher = getCurrentUtilLauncher();
    final int clientPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);

    final String logFile = getTestLogNamePrefix() + "-server" + "current"
        + ".log";
    final String[] startOps = new String[] { utilLauncher, "server", "start",
        "-dir=" + directory, "-J-Dgemfirexd.thrift-default=false",
        "-heap-size=1g", "-client-port=" + clientPort,
        "-log-file=" + logFile, "-locators=localhost[" + locatorPort + "]"
    // ,"-log-level=fine"
    };

    getLogWriter().info(
        "Starting current version GEMFIREXD server from " + directory
            + " on port " + clientPort + " launcher=" + utilLauncher);

    final Process serverProc = new ProcessBuilder(startOps).start();
    sleepForAsyncServerStart();
    return new ProcessStart(serverProc, clientPort, false, null, logFile);
  }

  public void stopCurrentVersionServer(String directory) throws Exception {
    String utilLauncher = getCurrentUtilLauncher();

    getLogWriter().info("Stopping current version GEMFIREXD server from "
        + directory + " launcher=" + utilLauncher);

    final String[] startOps = new String[] { utilLauncher, "server", "stop",
        "-dir=" + directory };

    Process serverProc = new ProcessBuilder(startOps).start();
    int exitValue = serverProc.waitFor();
    if (exitValue != 0) {
      getLogWriter().error(
          "Unexpected exit value " + exitValue
              + " while stopping current version server. Trying again.");
      Thread.sleep(5);
      serverProc = new ProcessBuilder(startOps).start();
      exitValue = serverProc.waitFor();
      if (exitValue != 0) {
        getLogWriter().error(
            "Unexpected exit value " + exitValue
                + " while stopping current version server in " + directory
                + ". Giving up.");
      }
    }
  }

  private static String getGFXDVersionDir(String versionDir) {
    return ABSOLUTE_PATH_PAT.matcher(versionDir).find()
        ? versionDir : GCM_WHERE + '/' + versionDir;
  }

  public ProcessStart startVersionedServer(String version, String versionDir,
      String workingDir, int mcastPort, int locatorPort, boolean withAuth)
      throws Exception {
    String gfxdDir = getGFXDVersionDir(versionDir);
    String utilLauncher = getUtilLauncher(gfxdDir);

    final int clientPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);

    final String logFile = getTestLogNamePrefix() + "-server" + version
        + ".log";

    final String[] startOps;
    if (locatorPort > 0) {
      if (withAuth) {
        startOps = new String[] { utilLauncher, "server", "start",
            "-dir=" + workingDir, "-client-port=" + clientPort,
            "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
            "-log-file=" + logFile, "-locators=localhost[" + locatorPort + "]",
            "-auth-provider=BUILTIN", "-gemfirexd.sql-authorization=TRUE",
            "-gemfirexd.user.SYSADMIN=SA", "-user=SYSADMIN", "-password=SA" };
      }
      else {
        startOps = new String[] { utilLauncher, "server", "start",
            "-dir=" + workingDir, "-client-port=" + clientPort,
            "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
            "-log-file=" + logFile, "-locators=localhost[" + locatorPort + "]"
        // ,"-log-level=fine"
        };
      }

    }
    else {
      startOps = new String[] { utilLauncher, "server", "start",
          "-dir=" + workingDir, "-client-port=" + clientPort,
          "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
          "-log-file=" + logFile, "-mcast-port=" + mcastPort };
    }
    getLogWriter().info(
        "Starting " + product +" server version " + version + " from " + gfxdDir + " on port "
            + clientPort);

    final Process serverProc = new ProcessBuilder(startOps).start();
    sleepForAsyncServerStart();
    return new ProcessStart(serverProc, clientPort, false, version, logFile);
  }

  public ProcessStart startVersionedServer(String version, String versionDir,
      String workingDir, int mcastPort, String locators, boolean withAuth,
      String persistDD) throws Exception {
    String gfxdDir = getGFXDVersionDir(versionDir);
    String utilLauncher = getUtilLauncher(gfxdDir);

    final int clientPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);

    final String logFile = getTestLogNamePrefix() + "-server" + version
        + ".log";

    final String[] startOps;
    if (locators != null) {
      if (withAuth) {
        startOps = new String[] { utilLauncher, "server", "start",
            "-dir=" + workingDir, "-client-port=" + clientPort,
            "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
            "-log-file=" + logFile, "-locators=" + locators,
            "-auth-provider=BUILTIN", "-gemfirexd.sql-authorization=TRUE",
            "-gemfirexd.user.SYSADMIN=SA", "-user=SYSADMIN", "-password=SA",
            "-persist-dd=" + persistDD };
      }
      else {
        startOps = new String[] { utilLauncher, "server", "start",
            "-dir=" + workingDir, "-client-port=" + clientPort,
            "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
            "-log-file=" + logFile, "-locators=" + locators,
            "-persist-dd=" + persistDD };
      }

    }
    else {
      startOps = new String[] { utilLauncher, "server", "start",
          "-dir=" + workingDir, "-client-port=" + clientPort,
          "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
          "-log-file=" + logFile, "-mcast-port=" + mcastPort };
    }
    getLogWriter().info(
        "Starting " + product + "server version " + version + " from " + gfxdDir + " on port "
            + clientPort);

    final Process serverProc = new ProcessBuilder(startOps).start();
    sleepForAsyncServerStart();
    return new ProcessStart(serverProc, clientPort, false, version, logFile);
  }

  public ProcessStart startVersionedLocator(int listIdx, int verIdx,
      int checkIdx, String workingDir, boolean withAuth) throws Exception {

    String version = compatibleVersionLists[listIdx][verIdx];
    String versionDir = compatibleVersionProductDirs[listIdx][verIdx];
    String checkVersion = checkIdx >= 0 ? "_"
        + compatibleVersionLists[listIdx][checkIdx] : "";

    return startVersionedLocator(version, versionDir, workingDir, withAuth);
  }

  public ProcessStart startVersionedLocator(String version, String versionDir,
      String workingDir, boolean withAuth) throws Exception {

    String gfxdDir = getGFXDVersionDir(versionDir);
    String utilLauncher = getUtilLauncher(gfxdDir);
    final int peerDiscoveryPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);

    final String logFile = getTestLogNamePrefix() + "-locator" + version
        + ".log";

    String[] startOps = null;

    if (withAuth) {
      startOps = new String[] { utilLauncher, "locator", "start",
          "-dir=" + workingDir, "-log-file=" + logFile,
          "-peer-discovery-port=" + peerDiscoveryPort, "-run-netserver=false",
          "-heap-size=512m", "-auth-provider=BUILTIN", "-gemfirexd.sql-authorization=TRUE",
          "-gemfirexd.user.SYSADMIN=SA", "-user=SYSADMIN", "-password=SA" };
    }
    else {
      startOps = new String[] { utilLauncher, "locator", "start",
          "-dir=" + workingDir, "-run-netserver=false", "-log-file=" + logFile,
          "-heap-size=512m", "-peer-discovery-port=" + peerDiscoveryPort };
    }

    getLogWriter().info(
        "Starting " + product + " locator version " + version + " from "
            + gfxdDir + " on port " + peerDiscoveryPort + " command-line: "
            + Arrays.toString(startOps));

    final Process serverProc = new ProcessBuilder(startOps).start();
    sleepForAsyncLocatorStart(serverProc, peerDiscoveryPort);
    return new ProcessStart(serverProc, peerDiscoveryPort, true, version,
        logFile);
  }

  public ProcessStart startVersionedLocator(String version, String versionDir,
      String workingDir, int peerDiscoveryPort, String locators,
      boolean withAuth, String persistDD) throws Exception {

    String gfxdDir = getGFXDVersionDir(versionDir);
    String utilLauncher = getUtilLauncher(gfxdDir);

    getLogWriter().info(
        "Starting locator version " + version + " from " + gfxdDir
            + " on port " + peerDiscoveryPort);

    final String logFile = getTestLogNamePrefix() + "-locator" + version
        + ".log";

    String[] startOps = null;

    if (withAuth) {
      startOps = new String[] { utilLauncher, "locator", "start",
          "-dir=" + workingDir, "-log-file=" + logFile,
          "-peer-discovery-port=" + peerDiscoveryPort, "-run-netserver=false",
          "-heap-size=512m", "-auth-provider=BUILTIN", "-gemfirexd.sql-authorization=TRUE",
          "-gemfirexd.user.SYSADMIN=SA", "-locators=" + locators,
          "-user=SYSADMIN", "-password=SA", "-persist-dd=" + persistDD };
    }
    else {
      startOps = new String[] { utilLauncher, "locator", "start",
          "-dir=" + workingDir, "-run-netserver=false",
          "-locators=" + locators, "-log-file=" + logFile,
          "-heap-size=512m", "-peer-discovery-port=" + peerDiscoveryPort,
          "-persist-dd=" + persistDD };
    }

    final Process serverProc = new ProcessBuilder(startOps).start();
    sleepForAsyncLocatorStart(serverProc, peerDiscoveryPort);
    return new ProcessStart(serverProc, peerDiscoveryPort, true, version,
        logFile);
  }

  protected void shutdownAll(int listIdx, int verIdx, int locatorPort)
      throws Exception {
    String version = compatibleVersionLists[listIdx][verIdx];
    String versionDir = compatibleVersionProductDirs[listIdx][verIdx];

    String gfxdDir = getGFXDVersionDir(versionDir);
    String utilLauncher = getUtilLauncher(gfxdDir);

    final String[] startOps = new String[] { utilLauncher, "shut-down-all",
        "-locators=localhost[" + locatorPort + "]" };

    final Process serverProc = new ProcessBuilder(startOps).start();
    final int exitValue = serverProc.waitFor();
    if (exitValue != 0) {
      throw new TestException("Unexpected exit value " + exitValue
          + " during shut-down-all for version " + version + '.');
    }
  }

  public void stopVersionedLocator(int listIdx, int verIdx, String workingDir)
      throws Exception {
    String version = compatibleVersionLists[listIdx][verIdx];
    String versionDir = compatibleVersionProductDirs[listIdx][verIdx];
    stopVersionedLocator(version, versionDir, workingDir);
  }

  public void stopVersionedLocator(String version, String versionDir,
      String workingDir) throws Exception {

    String gfxdDir = getGFXDVersionDir(versionDir);
    String utilLauncher = getUtilLauncher(gfxdDir);

    getLogWriter().info(
        "Stopping locator version " + version + " from " + gfxdDir);

    final String[] startOps = new String[] { utilLauncher, "locator", "stop",
        "-dir=" + workingDir };

    Process serverProc = new ProcessBuilder(startOps).start();
    int exitValue = serverProc.waitFor();
    if (exitValue != 0) {
      getLogWriter().error(
          "Unexpected exit value " + exitValue
              + " while stopping locator version " + version
              + ". Trying again.");
      Thread.sleep(5);
      serverProc = new ProcessBuilder(startOps).start();
      exitValue = serverProc.waitFor();
      if (exitValue != 0) {
        getLogWriter().error(
            "Unexpected exit value " + exitValue
                + " while stopping locator version " + version + " in "
                + gfxdDir + ". Giving up.");
      }
    }
  }

  public void stopVersionedServer(int listIdx, int verIdx, String workingDir)
      throws Exception {
    String version = compatibleVersionLists[listIdx][verIdx];
    String versionDir = compatibleVersionProductDirs[listIdx][verIdx];

    stopVersionedServer(version, versionDir, workingDir);
  }

  public void stopVersionedServer(String version, String versionDir,
      String workingDir) throws Exception {
    String gfxdDir = getGFXDVersionDir(versionDir);
    String utilLauncher = getUtilLauncher(gfxdDir);

    getLogWriter().info(
        "Stopping server version " + version + " from " + gfxdDir);

    final String[] startOps = new String[] { utilLauncher, "server", "stop",
        "-dir=" + workingDir };

    Process serverProc = new ProcessBuilder(startOps).start();
    int exitValue = serverProc.waitFor();
    if (exitValue != 0) {
      getLogWriter()
          .error(
              "Unexpected exit value " + exitValue
                  + " while stopping server version " + version
                  + ". Trying again.");
      Thread.sleep(5);
      serverProc = new ProcessBuilder(startOps).start();
      exitValue = serverProc.waitFor();
      if (exitValue != 0) {
        getLogWriter().error(
            "Unexpected exit value " + exitValue
                + " while stopping server version " + version + " in "
                + gfxdDir + ". Giving up.");
      }
    }
  }

  public ProcessStart startVersionedServerSG(String version, String versionDir,
      String workingDir, int mcastPort, int locatorPort, boolean withAuth,
      String serverGroups) throws Exception {
    String gfxdDir = getGFXDVersionDir(versionDir);
    String utilLauncher = getUtilLauncher(gfxdDir);

    final int clientPort = AvailablePort
        .getRandomAvailablePort(AvailablePort.SOCKET);

    final String logFile = getTestLogNamePrefix() + "-server" + version
        + ".log";

    final String[] startOps;
    if (locatorPort > 0) {
      if (withAuth) {
        startOps = new String[] { utilLauncher, "server", "start",
            "-dir=" + workingDir, "-client-port=" + clientPort,
            "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
            "-log-file=" + logFile, "-locators=localhost[" + locatorPort + "]",
            "-auth-provider=BUILTIN", "-gemfirexd.sql-authorization=TRUE",
            "-gemfirexd.user.SYSADMIN=SA", "-user=SYSADMIN", "-password=SA",
            "-server-groups=" + serverGroups, "-sync=false" };
      }
      else {
        startOps = new String[] { utilLauncher, "server", "start",
            "-dir=" + workingDir, "-client-port=" + clientPort,
            "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
            "-log-file=" + logFile, "-locators=localhost[" + locatorPort + "]",
            "-server-groups=" + serverGroups, "-sync=false" };
      }

    }
    else {
      startOps = new String[] { utilLauncher, "server", "start",
          "-dir=" + workingDir, "-client-port=" + clientPort,
          "-heap-size=1g", "-J-Dgemfirexd.thrift-default=false",
          "-log-file=" + logFile, "-mcast-port=" + mcastPort,
          "-server-groups=" + serverGroups, "-sync=false" };
    }
    getLogWriter().info(
        "Starting server version " + version + " from " + gfxdDir + " on port "
            + clientPort + " -server-groups=" + serverGroups);

    final Process serverProc = new ProcessBuilder(startOps).start();
    sleepForAsyncServerStart();
    return new ProcessStart(serverProc, clientPort, false, version, logFile);
  }

  protected void doWithVersionedClient(String product, String version,
      String versionDir, int clientPort, String doWhat) throws Exception {
    doWithVersionedClient(product, version, versionDir, clientPort, doWhat, false);
  }
  /**
   * Do operations using a client driver of specified product and version. A
   * static method of ProductClient class specified in the "what" parameter is
   * invoked and the client connection is passed to it. Operations can be coded
   * in the "what" method.
   * @param product SQLFIRE or GEMFIREXD
   * @param version Version
   * @param versionDir Version dir
   * @param clientPort client port to connect to
   * @param doWhat Static method of ProductClient to invoke
   * @param useSQLFireUrl Specifies whether JDBC URL of SQLFire should be used to connect
   * This applies only when the running server instances are GemFireXD.  For SQLFire instances
   * the SQLFire URL is used by default.
   */
  protected void doWithVersionedClient(String product, String version,
      String versionDir, int clientPort, String doWhat, boolean useSQLFireUrl) throws Exception {
    String clientJar = getClientJarPath(product, versionDir);
    final List<String> javaCommandLine = new ArrayList<String>();
    final File javaBinDir = new File(System.getProperty("java.home"), "bin");
    final File javaCommand = new File(javaBinDir, "java");

    javaCommandLine.add(javaCommand.getPath());
    javaCommandLine.add("-classpath");
    javaCommandLine.add(clientJar + File.pathSeparator
        + System.getenv("JUNIT_JAR") + File.pathSeparator
        + TestUtil.getResourcesDir());

    // add the ClientRun class
    javaCommandLine.add(ProductClient.class.getName());
    // arguments to ClientRun.main
    javaCommandLine.add(product);
    // String host = SocketCreator.getLocalHost().getHostName();
    String host = "localhost";
    javaCommandLine.add(host);
    javaCommandLine.add(String.valueOf(clientPort));
    javaCommandLine.add(clientJar);
    javaCommandLine.add(Boolean.toString(useSQLFireUrl));
    javaCommandLine.add(doWhat);
    System.out.println("Starting: " + javaCommandLine);

    ProcessBuilder builder = new ProcessBuilder(
        javaCommandLine.toArray(new String[javaCommandLine.size()]));
    final int[] exitValue = new int[] { -1 };
    // Redirect error stream to output stream so we can print the error
    builder.redirectErrorStream(true);
    final Process clientProc = builder.start();
    String clientOut = TestUtil.getProcessOutput(clientProc, 0, 90000,
        exitValue);
    System.out.println("Output from ProductClient using client "
        + clientJar + " : " + clientOut);
    if (exitValue[0] != 0) {
      throw new TestException("Unexpected exit value " + exitValue[0]
          + " while running client version " + version + ", OUTPUT=\n"
          + clientOut);
    }
  }

  protected void runVersionedClient(int listIdx, int verIdx, String host,
      int clientPort, int numOpsInBatch, int maxWaitMillis) throws Exception {
    String version = compatibleVersionLists[listIdx][verIdx];
    String versionDir = compatibleVersionProductDirs[listIdx][verIdx];
    String gfxdclientJar = GCM_WHERE + '/' + versionDir
        + "/product-gfxd/lib/gemfirexd-client.jar";
    getLogWriter().info(
        "Running client version " + version + " from " + gfxdclientJar);

    final List<String> javaCommandLine = new ArrayList<String>();
    final File javaBinDir = new File(System.getProperty("java.home"), "bin");
    final File javaCommand = new File(javaBinDir, "java");

    javaCommandLine.add(javaCommand.getPath());
    javaCommandLine.add("-classpath");
    javaCommandLine.add(gfxdclientJar + File.pathSeparator
        + System.getProperty("java.class.path"));

    // add the ClientRun class
    javaCommandLine.add(ClientRun.class.getName());
    // arguments to ClientRun.main
    javaCommandLine.add(host);
    javaCommandLine.add(String.valueOf(clientPort));
    javaCommandLine.add(String.valueOf(numOpsInBatch));
    javaCommandLine.add(gfxdclientJar);

    getLogWriter().info("Starting: " + javaCommandLine);

    final Process clientProc = new ProcessBuilder(
        javaCommandLine.toArray(new String[javaCommandLine.size()])).start();
    final int[] exitValue = new int[] { -1 };
    String clientOut = TestUtil.getProcessOutput(clientProc, 0,
        maxWaitMillis, exitValue);
    getLogWriter().info(
        "Output from ClientRun using client " + gfxdclientJar + " : "
            + clientOut);
    if (exitValue[0] != 0) {
      throw new TestException("Unexpected exit value " + exitValue[0]
          + " while running client version " + version);
    }
  }

  protected void runUpgradeDiskStore(String storeName, String[] dirs)
      throws Exception {

    for (String dir : dirs) {
      getLogWriter().info("Upgrading diskstore " + storeName + " from " + dir);
      String[] utilLauncherArgs = new String[] { "upgrade-disk-store",
          storeName, dir };
      GfxdUtilLauncher.main(utilLauncherArgs);
    }
  }

  protected String getClientJarPath(String product, String versionDir)
      throws Exception {
    String clientJar = null;
    if (product == null) {
      throw new Exception("Test error: PRODUCT must be set");
    }
    else if (product.equals(PRODUCT_SQLFIRE)) {
      clientJar = GCM_WHERE + "/" + versionDir
          + "/product-sqlf/lib/sqlfireclient.jar";
    }
    else if (product.equals(PRODUCT_GEMFIREXD)) {
      clientJar = GCM_WHERE + "/" + versionDir
          + "/product-gfxd/lib/gemfirexd-client.jar";
    }
    else {
      throw new Exception("Unknown value for PRODUCT");
    }
    return clientJar;
  }

  protected String getCurrentUtilLauncher() {
    String productPath = System.getenv("GEMFIREXD");
    final NativeCalls nc = NativeCalls.getInstance();
    final boolean isWindows = nc.getOSType().isWindows();
    if (productPath != null) {
      // getLogWriter().info("GEMFIREXD env variable:" + productPath);
      if (isWindows) {
        return productPath + "/bin/gfxd.bat";
      }
      else {
        return productPath + "/bin/gfxd";
      }
    }
    else {
      String jtestsDir = System.getProperty("JTESTS");
      String basePath = jtestsDir.replace("tests/classes", "");
      return getUtilLauncher(basePath);
    }
  }

  /*
   * Returns util launcher for the product.
   * Default is GemFireXD.  
   * To get launcher for SQLFire, set the product private variable.
   * This is to avoid a lot of argument passing through the start/stop methods.
   */
  protected String getUtilLauncher(final String gfxdDir) {
    final NativeCalls nc = NativeCalls.getInstance();
    String prod = "gfxd";
    if (product != null && product.equals(PRODUCT_SQLFIRE)) {
      prod = "sqlf";
    }
    if (nc.getOSType().isWindows()) {
      fail("Windows is currently not supported. See bug #51624");
      return gfxdDir + "/product-" + prod + "/bin/" + prod + ".bat";
    }
    else {
      return gfxdDir + "/product-" + prod + "/bin/" + prod;
    }
  }

  // Gets a client connection using gemfirexd URL
  protected Connection getNetConnection(String host, int port, Properties props)
      throws Exception {
    TestUtil.loadNetDriver();
    Connection conn = null;
    if (props == null) {
      props = new Properties();
    }
    int attempts = 1;
    // Try hard to get a connection
    while (true) {
      try {
        getLogWriter().info("getNetConnection:attempt-" + attempts);
        String url;
        // old jdbc:sqlfire:// scheme is no longer supported
        /*
        if (useOldUrl) {
          url = "jdbc:sqlfire://";
        }
        else {
          url = "jdbc:gemfirexd://";
        }
        */
        url = "jdbc:gemfirexd://";
        conn = DriverManager.getConnection(url + host + ":" + port, props);
        getLogWriter().info(
            "getNetConnection::client connection created with isolation level="
                + conn.getTransactionIsolation() + " and autocommit="
                + conn.getAutoCommit());
        break;
      } catch (SQLException e) {
        if (attempts++ < 10 && e.getSQLState().startsWith("08")) {
          Thread.sleep(1000);
          continue;
        }
        else {
          throw e;
        }
      }
    }
    return conn;
  }

  protected void cleanUpCustomStoreFiles(String[] dirs) {
    for (String dir : dirs) {
      File d = new File(dir);
      TestUtil.deleteDir(d);
    }
  }

  protected void deleteDefaultDiskStoreFiles(final String workingDir) {
    File[] persistFiles = new File(workingDir).listFiles(new FilenameFilter() {
      @Override
      public boolean accept(File dir, String name) {
        return name.startsWith("BACKUPGFXD-");
      }
    });
    if (persistFiles == null) {
      return;
    }
    for (File f : persistFiles) {
      f.delete();
    }
  }

  protected void cleanUp(String[] dirs) {
    // Controller's data stores
    deleteDataDictionaryDir();
    deleteDefaultDiskStoreFiles(getSysDirName());

    // Individual VM's datastores
    for (String dirName : dirs) {
      File dir = new File(dirName + "/datadictionary");
      TestUtil.deleteDir(dir);
      deleteDefaultDiskStoreFiles(dirName);
    }
  }
}
