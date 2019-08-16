/*
 * Copyright (c) 2017-2019 TIBCO Software Inc. All rights reserved.
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
package sql.snappy;

import java.io.*;
import java.net.UnknownHostException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.rmi.RemoteException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.*;

import com.gemstone.gemfire.LogWriter;
import com.gemstone.gemfire.SystemFailure;
import connectionPool.SnappyConnectionPoolPrms;
import connectionPool.TomcatConnectionPool;
import hydra.*;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.filefilter.IOFileFilter;
import org.apache.commons.io.filefilter.TrueFileFilter;
import org.apache.commons.io.filefilter.WildcardFileFilter;
import org.apache.commons.lang.StringUtils;
import sql.SQLHelper;
import sql.SQLPrms;
import sql.sqlutil.DMLStmtsFactory;
import util.PRObserver;
import util.StopStartPrms;
import util.StopStartVMs;
import util.TestException;
import util.TestHelper;

public class SnappyTest implements Serializable {

  protected static SnappyTest snappyTest;
  protected static HostDescription hd = TestConfig.getInstance().getMasterDescription()
      .getVmDescription().getHostDescription();
  protected static char sep = hd.getFileSep();
  private static String gemfireHome = hd.getGemFireHome() + sep;
  protected static String productDir = gemfireHome + ".." + sep + "snappy" + sep;
  protected static String productConfDirPath = productDir + "conf" + sep;
  private static String productLibsDir = productDir + "lib" + sep;
  private static String productSbinDir = productDir + "sbin" + sep;
  private static String productBinDir = productDir + "bin" + sep;
  protected static String SnappyShellPath = productBinDir + "snappy-sql";
  private static String dtests = gemfireHome + ".." + sep + ".." + sep + ".." + sep + "dtests" + sep;
  private static String dtestsLibsDir = dtests + "build-artifacts" + sep + "scala-2.11" + sep + "libs" + sep;
  private static String dtestsResourceLocation = dtests + "src" + sep + "resources" + sep;
  private static String dtestsScriptLocation = dtestsResourceLocation + "scripts" + sep;
  private static String dtestsDataLocation = dtestsResourceLocation + "data" + sep;
  private static String quickstartScriptLocation = productDir + "quickstart" + sep + "scripts" + sep;
  private static String quickstartDataLocation = productDir + "quickstart" + sep + "data" + sep;
  private static String logFile = null;

  private static Set<Integer> pids = new LinkedHashSet<Integer>();
  private static Set<File> dirList = new LinkedHashSet<File>();
  public static String userAppJar = null;
  private static String simulateStreamScriptName = TestConfig.tab().stringAt(SnappyPrms.simulateStreamScriptName, "simulateFileStream");
  private static String simulateStreamScriptDestinationFolder = TestConfig.tab().stringAt(SnappyPrms.simulateStreamScriptDestinationFolder, dtests);
  public static boolean isLongRunningTest = TestConfig.tab().booleanAt(SnappyPrms.isLongRunningTest, false);  //default to false
  public static boolean useRowStore = TestConfig.tab().booleanAt(SnappyPrms.useRowStore, false);  //default to false
  public static boolean isRestarted = false;
  public static boolean useSmartConnectorMode = TestConfig.tab().booleanAt(SnappyPrms.useSmartConnectorMode, false);  //default to false
  /*public static boolean useThinClientSmartConnectorMode = TestConfig.tab().booleanAt(SnappyPrms.useThinClientSmartConnectorMode, false);*/  //default to false
  public static boolean isStopMode = TestConfig.tab().booleanAt(SnappyPrms.isStopMode, false);  //default to false
  public static boolean forceStart = TestConfig.tab().booleanAt(SnappyPrms.forceStart,
      false);  //default to false
  public static boolean forceCopy = TestConfig.tab().booleanAt(SnappyPrms.forceCopy,
      false);  //default to false
  private static String primaryLocator = null;
  public static String leadHost = null;
  public static Long waitTimeBeforeStreamingJobStatus = TestConfig.tab().longAt(SnappyPrms.streamingJobExecutionTimeInMillis, 6000);
  private static Boolean logDirExists = false;
  private static Boolean doneCopying = false;
  protected static Boolean doneRandomizing = false;
  private static Boolean doneRestore = false;
  private static Boolean diskDirExists = false;
  private static Boolean runGemXDQuery = false;
  protected static int[] dmlTables = SQLPrms.getTables();
  public static final Random random = new Random(SQLPrms.getRandSeed());
  protected static DMLStmtsFactory dmlFactory = new DMLStmtsFactory();

  protected static boolean cycleVms = TestConfig.tab().booleanAt(SnappyPrms.cycleVms, false);
  public static final String LASTCYCLEDTIME = "lastCycledTime"; //used in SnappyBB
  public static final String LASTCYCLEDTIMEFORLEAD = "lastCycledTimeForLead"; //used in SnappyBB
  public static final String LASTCYCLEDTIMEFORLOCATOR = "lastCycledTimeForLocator"; //used in
  // SnappyBB
  public static long lastCycledTime = 0;
  public static long lastCycledTimeForLead = 0;
  public static int waitTimeBeforeNextCycleVM = TestConfig.tab().intAt(SnappyPrms.waitTimeBeforeNextCycleVM, 20); //secs
  public static final int THOUSAND = 1000;
  public static String cycleVMTarget = TestConfig.tab().stringAt(SnappyPrms.cycleVMTarget, "snappyStore");
  public static String cycleLeadVMTarget = TestConfig.tab().stringAt(SnappyPrms.cycleVMTarget, "lead");
  public static String cycleLocatorVMTarget = TestConfig.tab().stringAt(SnappyPrms.cycleVMTarget,
      "locator");
  //public static final String LEAD_PORT = "8090";
  public static final String MASTER_PORT = "7077";
  private static int jobSubmissionCount = 0;
  protected static String jarPath = gemfireHome + ".." + sep + ".." + sep + ".." + sep;

  private Connection connection = null;
  public static String connPool = TestConfig.tab().stringAt(SnappyConnectionPoolPrms.useConnPool, "");
  public static int connPoolType = SnappyConnectionPoolPrms.getConnPoolType(connPool);
  private static HydraThreadLocal localconnection = new HydraThreadLocal();

  /**
   * (String) APP_PROPS to set dynamically
   */
  public Map<Integer, String> dynamicAppProps = new HashMap<>();

  public enum SnappyNode {
    LOCATOR, SERVER, LEAD, WORKER
  }

  SnappyNode snappyNode;

  public SnappyTest() {
  }

  public SnappyTest(SnappyNode snappyNode) {
    this.snappyNode = snappyNode;
  }

  public static synchronized void HydraTask_initializeSnappyTest() {
    System.setProperty("gemfire.DISALLOW_RESERVE_SPACE", "true");
    if (snappyTest == null) {
      snappyTest = new SnappyTest();
      snappyTest.getClientHostDescription();
      int tid = RemoteTestModule.getCurrentThread().getThreadId();
      if (tid == 0) {
        snappyTest.generateConfig("locators");
        snappyTest.generateConfig("servers");
        snappyTest.generateConfig("leads");
      }
    }
  }


  public static void initSnappyArtifacts() {
    snappyTest = new SnappyTest();
    HostDescription hd = TestConfig.getInstance().getMasterDescription()
        .getVmDescription().getHostDescription();
    char sep = hd.getFileSep();
    String gemfireHome = hd.getGemFireHome() + sep;
    String productDir = gemfireHome + ".." + sep + "snappy" + sep;
    String productConfDirPath = productDir + "conf" + sep;
    String productLibsDir = productDir + "lib" + sep;
    String productSbinDir = productDir + "sbin" + sep;
    String productBinDir = productDir + "bin" + sep;
    String SnappyShellPath = productBinDir + "snappy-sql";
    String dtests = gemfireHome + ".." + sep + ".." + sep + ".." + sep + "dtests" + sep;
    String dtestsLibsDir = dtests + "build-artifacts" + sep + "scala-2.11" + sep + "libs" + sep;
    String dtestsResourceLocation = dtests + "src" + sep + "resources" + sep;
    String dtestsScriptLocation = dtestsResourceLocation + "scripts" + sep;
    String dtestsDataLocation = dtestsResourceLocation + "data" + sep;
    String quickstartScriptLocation = productDir + "quickstart" + sep + "scripts" + sep;
    String quickstartDataLocation = productDir + "quickstart" + sep + "data" + sep;
  }

  protected String getStoreTestsJar() {
    String storeTestsJar = hd.getTestDir() + hd.getFileSep() + ".." + hd.getFileSep() + ".." +
        hd.getFileSep() + ".." + hd.getFileSep() + "libs" + hd.getFileSep() + "snappydata-store-hydra-tests-" +
        ProductVersionHelper.getInfo().getProperty(ProductVersionHelper.SNAPPYRELEASEVERSION) +
        "-all.jar";
    return storeTestsJar;
  }

  protected void getClientHostDescription() {
    hd = TestConfig.getInstance()
        .getClientDescription(RemoteTestModule.getMyClientName())
        .getVmDescription().getHostDescription();
  }

  protected static String getUserAppJarLocation(final String jarName, String jarPath) {
    String userAppJarPath = null;
    File baseDir = new File(jarPath);
    try {
      IOFileFilter filter = new WildcardFileFilter(jarName);
      List<File> files = (List<File>) FileUtils.listFiles(baseDir, filter, TrueFileFilter.INSTANCE);
      Log.getLogWriter().info("Jar file found: " + Arrays.asList(files));
      for (File file1 : files) {
        if (!file1.getAbsolutePath().contains("/work/") || !file1.getAbsolutePath().contains("/scala-2.10/"))
          userAppJarPath = file1.getAbsolutePath();
      }
    } catch (Exception e) {
      Log.getLogWriter().info("Unable to find " + jarName + " jar at " + jarPath + " location.");
    }
    return userAppJarPath;
  }

  public String getDataLocation(String paramName) {
    if (paramName.equals(" ")) return paramName;
    String scriptPath = null;
    if (new File(paramName).exists()) {
      return paramName;
    } else {
      scriptPath = quickstartDataLocation + paramName;
      if (new File(scriptPath).exists()) return scriptPath;
      else scriptPath = dtestsDataLocation + paramName;
      if (new File(scriptPath).exists()) return scriptPath;
      else {
        String s = "Data doesn't exists at any expected location.";
        throw new TestException(s);
      }
    }
  }

  public String getScriptLocation(String scriptName) {
    String scriptPath = null;
    if (new File(scriptName).exists()) return scriptName;
    scriptPath = productSbinDir + scriptName;
    if (!new File(scriptPath).exists()) {
      scriptPath = productBinDir + scriptName;
      if (new File(scriptPath).exists()) return scriptPath;
      else
        scriptPath = getUserAppJarLocation(scriptName, dtestsScriptLocation);
      if (new File(scriptPath).exists()) return scriptPath;
      else
        scriptPath = quickstartScriptLocation + scriptName;
      if (new File(scriptPath).exists()) return scriptPath;
      else {
        String s = "Unable to find the script at any expected location.";
        throw new TestException(s);
      }
    }
    return scriptPath;
  }

  /**
   * Generates the configuration data required to start the snappy locator.
   */
  public static synchronized void HydraTask_generateSnappyLocatorConfig() {
    SnappyTest locator = new SnappyTest(SnappyNode.LOCATOR);
    locator.generateNodeConfig("locatorLogDir", false);
  }

  /**
   * Generates the configuration data required to start the snappy Server.
   */
  public static synchronized void HydraTask_generateSnappyServerConfig() {
    SnappyTest server = new SnappyTest(SnappyNode.SERVER);
    server.generateNodeConfig("serverLogDir", false);
  }

  /**
   * Generates the configuration data required to start the snappy Server.
   */
  public static synchronized void HydraTask_generateSnappyLeadConfig() {
    SnappyTest lead = new SnappyTest(SnappyNode.LEAD);
    lead.generateNodeConfig("leadLogDir", false);
  }

  protected void generateNodeConfig(String logDir, boolean returnNodeLogDir) {
    if (logDirExists) return;
    String addr = HostHelper.getHostAddress();
    int port = PortHelper.getRandomPort();
    String endpoint = addr + ":" + port;
    String clientPort = " -client-port=";
    String locators = " -locators=";
    String locPortString = " -peer-discovery-port=";
    String locatorsList = null;
    String dirPath = snappyTest.getLogDir();
    String nodeLogDir = null;
    String hostnameForPrimaryLocator = null;
    switch (snappyNode) {
      case LOCATOR:
        int locPort;
        do locPort = PortHelper.getRandomPort();
        while (locPort < 0 || locPort > 65535);
        nodeLogDir = HostHelper.getLocalHost() + " -dir=" + dirPath + clientPort + port +
            locPortString + locPort + SnappyPrms.getTimeStatistics() + SnappyPrms.getLogLevel() +
            " " + SnappyPrms.getLocatorLauncherProps();
        SnappyBB.getBB().getSharedMap().put("locatorHost" + "_" + RemoteTestModule.getMyVmid(),
            HostHelper.getLocalHost());
        SnappyBB.getBB().getSharedMap().put("locatorPort" + "_" + RemoteTestModule.getMyVmid(),
            Integer.toString(port));
        SnappyBB.getBB().getSharedMap().put("locatorMcastPort" + "_" + RemoteTestModule.getMyVmid
            (), Integer.toString(locPort));
        SnappyBB.getBB().getSharedMap().put("locators" + "_" + RemoteTestModule.getMyVmid(),
            HostHelper.getLocalHost() + ":" + Integer.toString(locPort));
        SnappyBB.getBB().getSharedMap().put(Integer.toString(locPort), HostHelper.getLocalHost());
        Log.getLogWriter().info("Generated locator endpoint: " + endpoint);
        SnappyNetworkServerBB.getBB().getSharedMap().put("locator" + "_" + RemoteTestModule
            .getMyVmid(), endpoint);
        int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB
            .primaryLocatorStarted);
        if (num == 1) {
          primaryLocator = endpoint;
          hostnameForPrimaryLocator = HostHelper.getLocalHost();
          SnappyBB.getBB().getSharedMap().put("primaryLocatorHost", hostnameForPrimaryLocator);
          SnappyBB.getBB().getSharedMap().put("primaryLocatorPort", Integer.toString(port));
        }
        break;
      case SERVER:
        locatorsList = getLocatorsList("locators");
        nodeLogDir = HostHelper.getLocalHost() + locators + locatorsList + " -dir=" +
            dirPath + clientPort + port + SnappyPrms.getServerMemory()
            + SnappyPrms.getConserveSockets() +
            " -J-Dgemfirexd.table-default-partitioned=" +
            SnappyPrms.getTableDefaultDataPolicy() + SnappyPrms.getTimeStatistics() +
            SnappyPrms.getLogLevel() + SnappyPrms.getCriticalHeapPercentage() +
            SnappyPrms.getEvictionHeapPercentage() + SnappyPrms.getPersistIndexes() +
            " -J-Dgemfire.CacheServerLauncher.SHUTDOWN_WAIT_TIME_MS=50000" +
            SnappyPrms.getFlightRecorderOptions(dirPath) +
            " -J-XX:+DisableExplicitGC" +
            " -J-XX:+HeapDumpOnOutOfMemoryError -J-XX:HeapDumpPath=" + dirPath +
            SnappyPrms.getGCOptions(dirPath) + " " +
            SnappyPrms.getServerLauncherProps() +
            " -classpath=" + getStoreTestsJar();
        Log.getLogWriter().info("Generated peer server endpoint: " + endpoint);
        SnappyBB.getBB().getSharedCounters().increment(SnappyBB.numServers);
        SnappyNetworkServerBB.getBB().getSharedMap().put("server" + "_" + RemoteTestModule
            .getMyVmid(), endpoint);
        break;
      case LEAD:
        locatorsList = getLocatorsList("locators");
        String leadHost;
        int leadPort = PortHelper.getRandomPort();
                /*do leadPort = PortHelper.getRandomPort();
                while (leadPort < 8091 || leadPort > 8099);*/
        nodeLogDir = HostHelper.getLocalHost() + locators + locatorsList +
            " -jobserver.waitForInitialization=true " +
            SnappyPrms.getExecutorCores() + SnappyPrms.getDriverMaxResultSize() +
            " -spark.local.dir=" + snappyTest.getTempDir("temp") +
            " -dir=" + dirPath + clientPort + port +
            " -jobserver.waitForInitialization=true " +
            SnappyPrms.getLeadMemory() + SnappyPrms.getSparkSqlBroadcastJoinThreshold()
            + " -spark.jobserver.port=" + leadPort + SnappyPrms.getSparkSchedulerMode()
            + /*" -spark.sql.inMemoryColumnarStorage.compressed="
                        + SnappyPrms.getCompressedInMemoryColumnarStorage() +*/
            SnappyPrms.getColumnBatchSize() + SnappyPrms.getConserveSockets() +
            " -table-default-partitioned=" + SnappyPrms.getTableDefaultDataPolicy() +
            " -J-XX:+DisableExplicitGC" + SnappyPrms.getTimeStatistics() +
            " -J-XX:+HeapDumpOnOutOfMemoryError -J-XX:HeapDumpPath=" + dirPath +
            SnappyPrms.getLogLevel() + SnappyPrms.getNumBootStrapTrials() +
            SnappyPrms.getClosedFormEstimates() + SnappyPrms.getZeppelinInterpreter() +
            " -classpath=" + getStoreTestsJar() +
            " -J-Dgemfire.CacheServerLauncher.SHUTDOWN_WAIT_TIME_MS=50000" +
            SnappyPrms.getFlightRecorderOptions(dirPath) +
            SnappyPrms.getGCOptions(dirPath) + " " +
            SnappyPrms.getLeaderLauncherProps() +
            " -spark.driver.extraClassPath=" + getStoreTestsJar() +
            " -spark.executor.extraClassPath=" + getStoreTestsJar();
        try {
          leadHost = HostHelper.getIPAddress().getLocalHost().getHostName();
        } catch (UnknownHostException e) {
          String s = "Lead host not found...";
          throw new HydraRuntimeException(s, e);
        }
        SnappyBB.getBB().getSharedMap().put("leadHost_" + RemoteTestModule.getMyClientName()
            + "_" + RemoteTestModule.getMyVmid(), leadHost);
        SnappyBB.getBB().getSharedMap().put("leadPort_" + RemoteTestModule
            .getMyClientName() + "_" + RemoteTestModule.getMyVmid(), Integer.toString(leadPort));
        break;
      case WORKER:
        nodeLogDir = HostHelper.getLocalHost();
        String sparkLogDir = "SPARK_LOG_DIR=" + hd.getUserDir();
        String sparkWorkerDir = "SPARK_WORKER_DIR=" + hd.getUserDir();
        /*String sparkMasterHost = (String) SnappyBB.getBB().getSharedMap().get("sparkMasterHost");
        if (sparkMasterHost == null) {
          try {
            String masterHost = HostHelper.getIPAddress().getLocalHost().getHostName();
            sparkMasterHost = "SPARK_MASTER_HOST=" + masterHost;
            Log.getLogWriter().info("sparkMasterHost is : " + masterHost);
            SnappyBB.getBB().getSharedMap().put("masterHost", masterHost);
            SnappyBB.getBB().getSharedMap().put("sparkMasterHost", sparkMasterHost);
          } catch (UnknownHostException e) {
            String s = "spark master host not found...";
            throw new HydraRuntimeException(s, e);
          }
        }*/
        SnappyBB.getBB().getSharedMap().put("sparkLogDir" + "_" + snappyTest.getMyTid(), sparkLogDir);
        SnappyBB.getBB().getSharedMap().put("sparkWorkerDir" + "_" + snappyTest.getMyTid(), sparkWorkerDir);
        break;
    }
    SnappyBB.getBB().getSharedMap().put(logDir + "_" + RemoteTestModule.getMyVmid() + "_" +
        snappyTest.getMyTid(), nodeLogDir);
    SnappyBB.getBB().getSharedMap().put("logDir_" + RemoteTestModule.getMyClientName() + "_" +
        RemoteTestModule.getMyVmid(), dirPath);
    Log.getLogWriter().info("nodeLogDir is : " + nodeLogDir);
    if (returnNodeLogDir)
      SnappyBB.getBB().getSharedMap().put("newNodelogDir" + "_" + RemoteTestModule.getMyVmid() +
          "_" + snappyTest.getMyTid(), nodeLogDir);
    logDirExists = true;
  }

  protected static Set<String> getFileContents(String userKey, Set<String> fileContents) {
    Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
    for (String key : keys) {
      if (key.startsWith(userKey)) {
        String value = (String) SnappyBB.getBB().getSharedMap().get(key);
        fileContents.add(value);
      }
    }
    return fileContents;
  }

  protected String getTempDir(String dirName) {
    File log = new File("");
    String dest = null;
    try {
      String dirString = log.getCanonicalPath();
      dest = log.getCanonicalPath() + File.separator + dirName;
    } catch (IOException e) {
      throw new TestException("IOException occurred while retriving destination logFile " +
          "path  " + log + "\nError Message:" + e.getMessage());
    }
    File tempDir = new File(dest);
    if (!tempDir.exists()) tempDir.mkdir();
    return tempDir.getAbsolutePath();
  }

  protected static Set<File> getDirList(String userKey) {
    Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
    for (String key : keys) {
      if (key.startsWith(userKey)) {
        File value = (File) SnappyBB.getBB().getSharedMap().get(key);
        dirList.add(value);
      }
    }
    return dirList;
  }

  protected static String getLocatorsList(String userKey) {
    String locatorsList = null;
    if (isLongRunningTest) {
      locatorsList = getDataFromFile("locatorList");
      if (locatorsList == null) locatorsList = getLocatorList(userKey);
    } else locatorsList = getLocatorList(userKey);
    return locatorsList;
  }

  protected static String getLocatorList(String userKey) {
    String locatorsList = null;
    Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
    Set<String> locatorHostPortList = new LinkedHashSet<>();
    for (String key : keys) {
      if (key.startsWith(userKey)) {
        String value = (String) SnappyBB.getBB().getSharedMap().get(key);
        locatorHostPortList.add(value);
      }
    }
    if (locatorHostPortList.size() == 0) {
      return "";
    }
    locatorsList = StringUtils.join(locatorHostPortList, ",");
    return locatorsList;
  }

  /**
   * Write the configuration data required to start the snappy locator/s in locators file under
   * conf directory at snappy build location.
   */
  public static void HydraTask_writeLocatorConfigData() {
    snappyTest.writeLocatorConfigData("locators", "locatorLogDir");
    if (isLongRunningTest) writeLocatorConnectionInfo();
  }

  /**
   * Write the configuration data required to start the snappy server/s in servers file under
   * conf directory at snappy build location.
   */
  public static void HydraTask_writeServerConfigData() {
    snappyTest.writeConfigData("servers", "serverLogDir");
  }

  /**
   * Write the configuration data required to start the snappy lead/s in leads file under conf
   * directory at snappy build location.
   */
  public static void HydraTask_writeLeadConfigData() {
    snappyTest.writeConfigData("leads", "leadLogDir");
    //if (isLongRunningTest) writeLeadHostInfo();
  }

  /**
   * Write the primary lead host,port info into the file(leadHost) under conf directory at
   * snappy build location. This is required for long running test scenarios where in  cluster
   * will be started  in first test and then rest all tests will use the same cluster
   */
  public static void HydraTask_writeLeadHostPortInfo() {
    writeLeadHostPortInfo();
  }

  /**
   * Write the primary locator host,port info into the file under conf directory at
   * snappy build location. This is required for long running test scenarios where in cluster
   * will be started in first test and then rest all tests will use the same cluster
   */
  public static void HydraTask_writePrimaryLocatorHostPortInfo() {
    writePrimaryLocatorHostPortInfo();
  }

  /**
   * Write the locator list containing host:port information for all locators in the snappy
   * cluster into the file(locatorList) under conf directory at snappy build location.
   * This is required for long running test scenarios where in cluster
   * will be started in first test and then rest all tests will use the same cluster
   */
  public static void HydraTask_writeLocatorInfo() {
    writeLocatorInfo();
  }

  protected void writeConfigData(String fileName, String logDir) {
    String filePath = productConfDirPath + fileName;
    File file = new File(filePath);
    if (fileName.equalsIgnoreCase("spark-env.sh")) file.setExecutable(true);
    Set<String> fileContent = new LinkedHashSet<String>();
    fileContent = snappyTest.getFileContents(logDir, fileContent);
    if (fileContent.size() == 0) {
      String s = "No data found for writing to " + fileName + " file under conf directory";
      throw new TestException(s);
    }
    for (String s : fileContent) {
      snappyTest.writeToFile(s, file, true);
    }
  }

  protected void writeLocatorConfigData(String fileName, String logDir) {
    String filePath = productConfDirPath + fileName;
    File file = new File(filePath);
    String peerDiscoveryPort = null;
    Set<String> fileContent = new LinkedHashSet<String>();
    fileContent = snappyTest.getFileContents(logDir, fileContent);
    if (fileContent.size() == 0) {
      String s = "No data found for writing to " + fileName + " file under conf directory";
      throw new TestException(s);
    }
    String locatorsList = getLocatorsList("locators");
    for (String s : fileContent) {
      String[] splited = s.split(" ");
      for (String str : splited) {
        String peerDiscoveryPortString = "-peer-discovery-port=";
        if (str.contains(peerDiscoveryPortString)) {
          peerDiscoveryPort = str.substring(str.indexOf("=") + 1);
        }
      }
      String host = (String) SnappyBB.getBB().getSharedMap().get(peerDiscoveryPort);
      String replaceString = host + ":" + peerDiscoveryPort + ",";
      String newLocatorsList;
      if (locatorsList.contains(replaceString)) {
        newLocatorsList = locatorsList.replace(replaceString, "");
      } else {
        replaceString = host + ":" + peerDiscoveryPort;
        newLocatorsList = locatorsList.replace(replaceString, "");
      }
      String nodeLogDir = s.concat(" -locators=" + newLocatorsList);
      snappyTest.writeToFile(nodeLogDir, file, true);
    }
  }

  protected void writeNodeConfigData(String fileName, String nodeLogDir, boolean append) {
    String filePath = productConfDirPath + fileName;
    File file = new File(filePath);
    snappyTest.writeToFile(nodeLogDir, file, append);
  }

  /**
   * Returns all network locator endpoints from the {@link
   * SnappyNetworkServerBB} map, a possibly empty list.  This includes all
   * network servers that have ever started, regardless of their distributed
   * system or current active status.
   */
  public static List getNetworkLocatorEndpoints() {
    return getEndpoints("locator");
  }

  /**
   * Returns all network server endpoints from the {@link
   * SnappyNetworkServerBB} map, a possibly empty list.  This includes all
   * network servers that have ever started, regardless of their distributed
   * system or current active status.
   */
  public static List getNetworkServerEndpoints() {
    return getEndpoints("server");
  }

  protected HashMap getclientHostPort() {
    HashMap<String, Integer> hostPort = new HashMap<String, Integer>();
    String endpoint = null;
    List<String> endpoints = getNetworkLocatorEndpoints();
    if (endpoints.size() == 0) {
      if (isLongRunningTest) {
        endpoints = getLocatorEndpointFromFile();
      }
    }
    endpoint = endpoints.get(0);
    String host = endpoint.substring(0, endpoint.indexOf(":"));
    Log.getLogWriter().info("Client Host is:" + host);
    String port = endpoint.substring(endpoint.indexOf(":") + 1);
    int clientPort = Integer.parseInt(port);
    Log.getLogWriter().info("Client Port is :" + clientPort);
    hostPort.put(host, clientPort);
    return hostPort;
  }

  /**
   * Returns all endpoints of the given type.
   */
  private static synchronized List<String> getEndpoints(String type) {
    List<String> endpoints = new ArrayList();
    Set<String> keys = SnappyNetworkServerBB.getBB().getSharedMap().getMap().keySet();
    for (String key : keys) {
      if (key.startsWith(type.toString())) {
        String endpoint = (String) SnappyNetworkServerBB.getBB().getSharedMap().getMap().get(key);
        endpoints.add(endpoint);
      }
    }
    Log.getLogWriter().info("Returning endpoint list: " + endpoints);
    return endpoints;
  }


  /**
   * Returns PIDs for all the processes started in the test, e.g. locator, server, lead .
   */
  private static synchronized Set<String> getPidList() {
    Set<String> pidList = new HashSet<>();
    Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
    for (String key : keys) {
      if (key.startsWith("pid")) {
        String pid = (String) SnappyBB.getBB().getSharedMap().getMap().get(key);
        pidList.add(pid);
      }
    }
    Log.getLogWriter().info("Returning pid list: " + pidList);
    return pidList;
  }


  /**
   * Returns hostname of the process
   */
  protected static synchronized String getPidHost(String pid) {
    Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
    String pidHost = null;
    for (String key : keys) {
      if (key.startsWith("host") && key.contains(pid)) {
        pidHost = (String) SnappyBB.getBB().getSharedMap().getMap().get(key);
      }
    }
    Log.getLogWriter().info("PID Host for : " + pid + " : " + pidHost);
    return pidHost;
  }


  /**
   * Returns primary lead port .
   */
  private static synchronized String getPrimaryLeadPort(String clientName) {
    List<String> portList = new ArrayList();
    String port = null;
    Set<String> keys = SnappyBB.getBB().getSharedMap().getMap().keySet();
    for (String key : keys) {
      if (key.startsWith("leadPort_") && key.contains(clientName)) {
        port = (String) SnappyBB.getBB().getSharedMap().getMap().get(key);
      }
    }
    Log.getLogWriter().info("Returning primary lead port: " + port);
    return port;
  }


  protected void initHydraThreadLocals() {
    this.connection = getConnection();
  }

  protected Connection getConnection() {
    Connection connection = (Connection) localconnection.get();
    return connection;
  }

  protected void setConnection(Connection connection) {
    localconnection.set(connection);
  }

  protected void updateHydraThreadLocals() {
    setConnection(this.connection);
  }

  public static void HydraTask_getClientConnection_Snappy() throws SQLException {
    SnappyTest st = new SnappyTest();
    st.connectThinClient();
    st.updateHydraThreadLocals();
  }

  private void connectThinClient() throws SQLException {
    connection = getLocatorConnection();
  }

  public static Connection getClientConnection() {
    SnappyTest st = new SnappyTest();
    st.initHydraThreadLocals();
    return st.getConnection();
  }

  public static void HydraTask_getClientConnection() throws SQLException {
    getLocatorConnection();
  }

  /**
   * Mandatory to use this method in case of HA test.
   * As per current implementation, for starting the locator snappy-locators.sh script is used,
   * which starts the locators based on the data in locators conf file.
   * In HA test, the framework deletes the old locators file and creates the new one with the
   * config data specific to locator which is getting recycled.
   * So, we need to backup the original locators conf file. This will be required at the end of the
   * test for stopping all locators which have been started in the test.
   **/
  public static synchronized void backUpLocatorConfigData() {
    snappyTest.copyConfigData("locators");
  }

  /**
   * Mandatory to use this method in case of HA test.
   * As per current implementation, for starting the server snappy-servers.sh script is used, which starts
   * the servers based on the data in servers conf file.
   * In HA test, the framework deletes the old servers file and creates the new one with the config data specific
   * to server which is getting recycled.
   * So, we need to backup the original servers conf file. This will be required at the end of the test for stopping all servers
   * which have been started in the test.
   **/
  public static synchronized void backUpServerConfigData() {
    snappyTest.copyConfigData("servers");
  }

  /**
   * Mandatory to use this method in case of HA test.
   * As per current implementation, for starting the locator snappy-locators.sh script is used,
   * which starts the locators based on the data in locators conf file.
   * In HA test, the framework deletes the old locators file and creates the new one with the
   * config data specific to locator which is getting recycled.
   * So, we need to restore the original locators conf file. This will be required at the end of
   * the test for stopping all locators which have been started in the test.
   **/
  public static synchronized void restoreLocatorConfigData() {
    snappyTest.restoreConfigData("locators");
  }

  /**
   * Mandatory to use this method in case of HA test.
   * As per current implementation, for starting the server snappy-servers.sh script is used, which starts
   * the servers based on the data in servers conf file.
   * In HA test, the framework deletes the old servers file and creates the new one with the config data specific
   * to server which is getting recycled.
   * So, we need to restore the original servers conf file. This will be required at the end of the test for stopping all servers
   * which have been started in the test.
   **/
  public static synchronized void restoreServerConfigData() {
    snappyTest.restoreConfigData("servers");
  }

  /**
   * Mandatory to use this method in case of HA test.
   * As per current implementation, for starting the lead members, snappy-leads.sh script is used, which starts
   * the lead members based on the data in leads conf file.
   * In HA test, the framework deletes the old leads file and creates the new one with the config data specific
   * to lead member which is getting recycled.
   * So, we need to restore the original leads conf file. This will be required at the end of the test for stopping all leads
   * which have been started in the test.
   **/
  public static synchronized void restoreLeadConfigData() {
    snappyTest.restoreConfigData("leads");
  }

  /**
   * Mandatory to use this method in case of HA test.
   * As per current implementation, for starting the lead members, snappy-leads.sh script is used, which starts
   * the lead members based on the data in leads conf file.
   * In HA test, the framework deletes the old leads file and creates the new one with the config data specific
   * to lead member which is getting recycled.
   * So, we need to backup the original leads conf file. This will be required at the end of the test for stopping all leads
   * which have been started in the test.
   **/
  public static synchronized void backUpLeadConfigData() {
    snappyTest.copyConfigData("leads");
  }

  protected void copyConfigData(String fileName) {
    if (doneCopying && !forceCopy) return;
    String filePath = productConfDirPath + fileName;
    File srcFile = new File(filePath);
    try {
      File destDir = new File("");
      FileUtils.copyFileToDirectory(srcFile, destDir);
      Log.getLogWriter().info("Done copying " + fileName + " file from " + srcFile + " to " + destDir.getAbsolutePath());
    } catch (IOException e) {
      throw new TestException("Error occurred while copying data from file: " + srcFile + "\n " + e.getMessage());
    }
    doneCopying = true;
  }

  protected void restoreConfigData(String fileName) {
    if (doneRestore) return;
    String filePath = productConfDirPath + fileName;
    File srcDir = new File("");
    File srcFile = null, destDir = null;
    try {
      String srcFilePath = srcDir.getCanonicalPath() + File.separator + fileName;
      srcFile = new File(srcFilePath);
      destDir = new File(filePath);
      if (destDir.exists()) destDir.delete();
      destDir = new File(productConfDirPath);
      FileUtils.copyFileToDirectory(srcFile, destDir);
      Log.getLogWriter().info("Done restoring " + fileName + " file from " + srcFile.getAbsolutePath() + " to " + destDir);
    } catch (IOException e) {
      throw new TestException("Error occurred while copying data from file: " + srcFile + "\n " + e.getMessage());
    }
    doneRestore = true;
  }

  /**
   * Writes the locator host:port information to the locatorConnInfo file under conf directory.
   */
  protected static void writeLocatorConnectionInfo() {
    List<String> endpoints = validateLocatorEndpointData();
    snappyTest.writeNodeConfigData("locatorConnInfo", endpoints.get(0), true);
  }

  /**
   * Writes the comma seperated host:port list of all locators started in a cluster to the
   * locatorList file under conf directory.
   */
  protected static void writeLocatorInfo() {
    String locatorList = getLocatorsList("locators");
    snappyTest.writeNodeConfigData("locatorList", locatorList, false);
  }

  /**
   * Writes the lead host information to the leadHost file under conf directory.
   */
  protected static void writeLeadHostPortInfo() {
    leadHost = snappyTest.getLeadHost();
    snappyTest.writeNodeConfigData("leadHost", leadHost, false);
    String leadPort = snappyTest.getLeadPort();
    snappyTest.writeNodeConfigData("leadPort", leadPort, false);
  }

  protected static void writePrimaryLocatorHostPortInfo() {
    String primaryLocatorHost = getPrimaryLocatorHost();
    String primaryLocatorPort = getPrimaryLocatorPort();
    snappyTest.writeNodeConfigData("primaryLocatorHost", primaryLocatorHost, false);
    snappyTest.writeNodeConfigData("primaryLocatorPort", primaryLocatorPort, false);
  }

  protected static String getDataFromFile(String fileName) {
    File logFile = getLogFile(fileName);
    String data = null;
    try {
      BufferedReader br = readDataFromFile(logFile);
      String str = null;
      while ((str = br.readLine()) != null) {
        data = str;
      }
      br.close();
    } catch (IOException e) {
      String s = "Problem while reading the file : " + logFile.getAbsolutePath();
      throw new TestException(s, e);
    }
    return data;
  }

  protected static BufferedReader readDataFromFile(File filename) {
    BufferedReader br = null;
    try {
      FileInputStream fis = new FileInputStream(filename);
      br = new BufferedReader(new InputStreamReader(fis));
    } catch (FileNotFoundException e) {
      String s = "Unable to find file: " + filename.getAbsolutePath();
      throw new TestException(s);
    }
    return br;
  }

  protected static File getLogFile(String filename) {
    String dest = productConfDirPath + filename;
    File logFile = new File(dest);
    if (!logFile.exists()) try {
      logFile.createNewFile();
    } catch (IOException e) {
      String s = "Unable to create file: " + logFile.getAbsolutePath();
      throw new TestException(s);
    }
    return logFile;
  }

  protected static List<String> getLocatorEndpointFromFile() {
    List<String> endpoints = new ArrayList<String>();
    File logFile = getLogFile("locatorConnInfo");
    try {
      BufferedReader br = readDataFromFile(logFile);
      String str = null;
      while ((str = br.readLine()) != null) {
        endpoints.add(str);
      }
      br.close();
    } catch (IOException e) {
      String s = "Problem while reading the file : " + logFile.getAbsolutePath();
      throw new TestException(s, e);
    }
    return endpoints;
  }

  public static List<String> validateLocatorEndpointData() {
    List<String> endpoints = getNetworkLocatorEndpoints();
    if (endpoints.size() == 0) {
      if (isLongRunningTest) {
        endpoints = getLocatorEndpointFromFile();
      }
    }
    if (endpoints.size() == 0) {
      String s = "No network locator endpoints found";
      throw new TestException(s);
    }
    return endpoints;
  }

  protected static List<String> validateServerEndpointData() {
    List<String> endpoints = getNetworkServerEndpoints();
    /*if (endpoints.size() == 0) {
      if (isLongRunningTest) {
        endpoints = getServerConnection();
      }
    }*/
    if (endpoints.size() == 0) {
      String s = "No network server endpoints found";
      throw new TestException(s);
    }
    return endpoints;
  }

  public static synchronized void setConnPoolType(){
    if(!SnappyBB.getBB().getSharedMap().containsKey("connPoolType"))
      SnappyBB.getBB().getSharedMap().put("connPoolType", SnappyConnectionPoolPrms
          .getConnPoolType(connPool));
    connPoolType = (int)SnappyBB.getBB().getSharedMap().get("connPoolType");
  }

  /**
   * Gets Client connection.
   */
  public static Connection getLocatorConnection() throws SQLException {
    Connection conn = null;

    if(!SnappyBB.getBB().getSharedMap().containsKey("connPoolType"))
      setConnPoolType();
    else 
      connPoolType = (int)SnappyBB.getBB().getSharedMap().get("connPoolType");
    
/*    if(connPoolType == 0){
      conn = HikariConnectionPool.getConnection();
    } else*/ if (connPoolType == 1){
      conn = TomcatConnectionPool.getConnection();
    }
    else {
      List<String> endpoints = validateLocatorEndpointData();
      Log.getLogWriter().info("Obtained endpoints, now getting connection");
      if (!runGemXDQuery) {
        String url = "jdbc:snappydata://" + endpoints.get(0);
        Log.getLogWriter().info("url is " + url);
        conn = getConnection(url, "io.snappydata.jdbc.ClientDriver");
      } else {
        String url = "jdbc:gemfirexd://" + endpoints.get(0);
        Log.getLogWriter().info("url is " + url);
        conn = getConnection(url, "io.snappydata.jdbc.ClientDriver");
      }
    }
    return conn;
  }

  public static Connection getLocatorConnectionUsingProps() throws
      SQLException {
    List<String> endpoints = validateLocatorEndpointData();
    Properties props = new Properties();
    Vector connPropsList = SnappyPrms.getConnPropsList();
    for (int i = 0; i < connPropsList.size(); i++) {
      String conProp = (String) connPropsList.elementAt(i);
      String conPropKey = conProp.substring(0, conProp.indexOf("="));
      String conPropValue = conProp.substring(conProp.indexOf("=") + 1);
      props.setProperty(conPropKey, conPropValue);
    }
    Connection conn = null;
    String url = "jdbc:snappydata://" + endpoints.get(0) + "/";
    conn = getConnection(url, "io.snappydata.jdbc.ClientDriver", props);
    return conn;
  }

  public static Connection getServerConnection() throws SQLException {
    List<String> endpoints = validateServerEndpointData();
    Connection conn = null;
    if (!runGemXDQuery) {
      String url = "jdbc:snappydata://" + endpoints.get(0);
      Log.getLogWriter().info("url is " + url);
      conn = getConnection(url, "io.snappydata.jdbc.ClientDriver");
    } else {
      String url = "jdbc:gemfirexd://" + endpoints.get(0);
      Log.getLogWriter().info("url is " + url);
      conn = getConnection(url, "io.snappydata.jdbc.ClientDriver");
    }
    return conn;
  }

  private static Connection getConnection(String protocol, String driver) throws SQLException {
    Log.getLogWriter().info("Creating connection using " + driver + " with " + protocol);
    loadDriver(driver);
    Connection conn = DriverManager.getConnection(protocol);
    return conn;
  }

  private static Connection getConnection(String protocol, String driver, Properties props)
      throws
      SQLException {
    Log.getLogWriter().info("Creating connection using " + driver + " with " + protocol +
        " and user specified properties list: = " + props.toString());
    loadDriver(driver);
    Connection conn = DriverManager.getConnection(protocol, props);
    return conn;
  }

  public static void closeConnection(Connection conn) {
    try {
      conn.close();
    } catch (SQLException e) {
      SQLHelper.printSQLException(e);
      throw new TestException("Not able to release the connection " + TestHelper.getStackTrace(e));
    }
  }

  public void commit(Connection conn) {
    if (conn == null) return;
    try {
      Log.getLogWriter().info("committing the ops.. ");
      conn.commit();
    } catch (SQLException se) {
      SQLHelper.handleSQLException(se);
    }
  }

  /**
   * The JDBC driver is loaded by loading its class.  If you are using JDBC 4.0
   * (Java SE 6) or newer, JDBC drivers may be automatically loaded, making
   * this code optional.
   * <p/>
   * In an embedded environment, any static Derby system properties
   * must be set before loading the driver to take effect.
   */
  public static void loadDriver(String driver) {
    try {
      Class.forName(driver).newInstance();
    } catch (ClassNotFoundException e) {
      String s = "Problem loading JDBC driver: " + driver;
      throw new TestException(s, e);
    } catch (InstantiationException e) {
      String s = "Problem loading JDBC driver: " + driver;
      throw new TestException(s, e);
    } catch (IllegalAccessException e) {
      String s = "Problem loading JDBC driver: " + driver;
      throw new TestException(s, e);
    }
  }

  protected void writeToFile(String logDir, File file, boolean append) {
    try {
      FileWriter fw = new FileWriter(file.getAbsoluteFile(), append);
      BufferedWriter bw = new BufferedWriter(fw);
      bw.write(logDir);
      bw.newLine();
      bw.close();
    } catch (IOException e) {
      throw new TestException("Error occurred while writing to a file: " + file +
          e.getMessage());
    }
  }

  /**
   * Executes user SQL scripts.
   */
  public static synchronized void HydraTask_executeSQLScripts() {
    Vector scriptNames, dataLocationList = null, persistenceModeList = null,
        colocateWithOptionList = null, partitionByOptionList = null, numPartitionsList =
        null, redundancyOptionList = null, recoverDelayOptionList = null,
        maxPartitionSizeList = null, evictionByOptionList = null;
    File log = null, logFile = null;
    scriptNames = SnappyPrms.getSQLScriptNames();
    if (scriptNames == null) {
      String s = "No Script names provided for executing in the Hydra TASK";
      throw new TestException(s);
    }
    try {
      dataLocationList = SnappyPrms.getDataLocationList();
      persistenceModeList = SnappyPrms.getPersistenceModeList();
      colocateWithOptionList = SnappyPrms.getColocateWithOptionList();
      partitionByOptionList = SnappyPrms.getPartitionByOptionList();
      numPartitionsList = SnappyPrms.getNumPartitionsList();
      redundancyOptionList = SnappyPrms.getRedundancyOptionList();
      recoverDelayOptionList = SnappyPrms.getRecoverDelayOptionList();
      maxPartitionSizeList = SnappyPrms.getMaxPartitionSizeList();
      evictionByOptionList = SnappyPrms.getEvictionByOptionList();
      if (dataLocationList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \" \" parameter in the dataLocationList for the  " +
            "scripts for which no dataLocation is specified.");
        while (dataLocationList.size() != scriptNames.size())
          dataLocationList.add(" ");
      }
      if (persistenceModeList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \"sync\" parameter in the persistenceModeList for" +
            "  the scripts for which no persistence mode is specified.");
        while (persistenceModeList.size() != scriptNames.size())
          persistenceModeList.add("sync");
      }
      if (colocateWithOptionList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \"none\" parameter in the colocateWithOptionList " +
            " for the scripts for which no COLOCATE_WITH Option is specified.");
        while (colocateWithOptionList.size() != scriptNames.size())
          colocateWithOptionList.add("none");
      }
      if (partitionByOptionList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \" \" parameter in the partitionByOptionList for " +
            " the scripts for which no PARTITION_BY option is specified.");
        while (partitionByOptionList.size() != scriptNames.size())
          partitionByOptionList.add(" ");
      }
      if (numPartitionsList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \"113\" parameter in the partitionByOptionsList " +
            "for  the scripts for which no BUCKETS option is specified.");
        while (numPartitionsList.size() != scriptNames.size())
          numPartitionsList.add("113");
      }
      if (redundancyOptionList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \" \" parameter in the redundancyOptionList for " +
            "the  scripts for which no REDUNDANCY option is specified.");
        while (redundancyOptionList.size() != scriptNames.size())
          redundancyOptionList.add(" ");
      }
      if (recoverDelayOptionList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \" \" parameter in the recoverDelayOptionList for" +
            "  the scripts for which no RECOVER_DELAY option is specified.");
        while (recoverDelayOptionList.size() != scriptNames.size())
          recoverDelayOptionList.add(" ");
      }
      if (maxPartitionSizeList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \" \" parameter in the maxPartitionSizeList for " +
            "the  scripts for which no MAX_PART_SIZE option is specified.");
        while (maxPartitionSizeList.size() != scriptNames.size())
          maxPartitionSizeList.add(" ");
      }
      if (evictionByOptionList.size() != scriptNames.size()) {
        Log.getLogWriter().info("Adding \"LRUHEAPPERCENT\" parameter in the  " +
            "evictionByOptionList for the scripts for which no EVICTION_BY option is" +
            " specified.");
        while (evictionByOptionList.size() != scriptNames.size())
          evictionByOptionList.add("LRUHEAPPERCENT");
      }
      for (int i = 0; i < scriptNames.size(); i++) {
        String userScript = (String) scriptNames.elementAt(i);
        String location = (String) dataLocationList.elementAt(i);
        String persistenceMode = (String) persistenceModeList.elementAt(i);
        String colocateWith = (String) colocateWithOptionList.elementAt(i);
        String partitionBy = (String) partitionByOptionList.elementAt(i);
        String numPartitions = (String) numPartitionsList.elementAt(i);
        String redundancy = (String) redundancyOptionList.elementAt(i);
        String recoverDelay = (String) recoverDelayOptionList.elementAt(i);
        String maxPartitionSize = (String) maxPartitionSizeList.elementAt(i);
        String evictionByOption = (String) evictionByOptionList.elementAt(i);
        String dataLocation = snappyTest.getDataLocation(location);
        String filePath = snappyTest.getScriptLocation(userScript);
        log = new File("");
        String dest = log.getCanonicalPath() + File.separator + "sqlScriptsResult_" +
            RemoteTestModule.getCurrentThread().getThreadId() + ".log";
        logFile = new File(dest);
        String primaryLocatorHost = getPrimaryLocatorHost();
        String primaryLocatorPort = getPrimaryLocatorPort();
        ProcessBuilder pb = new ProcessBuilder(SnappyShellPath, "run", "-file=" +
            filePath, "-param:dataLocation=" + dataLocation,
            "-param:persistenceMode=" + persistenceMode, "-param:colocateWith=" +
            colocateWith, "-param:partitionBy=" + partitionBy,
            "-param:numPartitions=" + numPartitions, "-param:redundancy=" +
            redundancy, "-param:recoverDelay=" + recoverDelay,
            "-param:maxPartitionSize=" + maxPartitionSize, "-param:evictionByOption="
            + evictionByOption, "-client-port=" + primaryLocatorPort,
            "-client-bind-address=" + primaryLocatorHost);
        snappyTest.executeProcess(pb, logFile);
      }
    } catch (IOException e) {
      throw new TestException("IOException occurred while retriving destination logFile " +
          "path  " + log + "\nError Message:" + e.getMessage());
    }
  }

  protected static String getPrimaryLocatorHost() {
    String primaryLocatorHost = null;
    if (isLongRunningTest) {
      primaryLocatorHost = getDataFromFile("primaryLocatorHost");
      if (primaryLocatorHost == null)
        primaryLocatorHost = (String) SnappyBB.getBB().getSharedMap().get
            ("primaryLocatorHost");
    } else
      primaryLocatorHost = (String) SnappyBB.getBB().getSharedMap().get("primaryLocatorHost");
    return primaryLocatorHost;
  }

  protected static String getPrimaryLocatorPort() {
    String primaryLocatorPort = null;
    if (isLongRunningTest) {
      primaryLocatorPort = getDataFromFile("primaryLocatorPort");
      if (primaryLocatorPort == null)
        primaryLocatorPort = (String) SnappyBB.getBB().getSharedMap().get
            ("primaryLocatorPort");
    } else
      primaryLocatorPort = (String) SnappyBB.getBB().getSharedMap().get("primaryLocatorPort");
    return primaryLocatorPort;
  }

  public void executeProcess(ProcessBuilder pb, File logFile) {
    Process p = null;
    try {
      if (logFile != null) {
        pb.redirectErrorStream(true);
        pb.redirectError(ProcessBuilder.Redirect.PIPE);
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
      }
      p = pb.start();
      if (logFile != null) {
        assert pb.redirectInput() == ProcessBuilder.Redirect.PIPE;
        assert pb.redirectOutput().file() == logFile;
        assert p.getInputStream().read() == -1;
      }
      int rc = p.waitFor();
      if ((rc == 0) || (pb.command().contains("grep") && rc == 1)) {
        Log.getLogWriter().info("Executed successfully");
      } else {
        Log.getLogWriter().info("Failed with exit code: " + rc);
      }
    } catch (IOException e) {
      throw new TestException("Exception occurred while starting the process:" + pb +
          "\nError Message:" + e.getMessage());
    } catch (InterruptedException e) {
      throw new TestException("Exception occurred while waiting for the process execution:"
          + p + "\nError Message:" + e.getMessage());
    }
  }

  protected synchronized void recordSnappyProcessIDinNukeRun(String pName) {
    Process pr = null;
    try {
      File log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "PIDs_" + pName + "_" + HostHelper
          .getLocalHost() +
          ".log";
      File logFile = new File(dest);
      if (!logFile.exists()) {
        String command;
        if (pName.equals("Master"))
          command = "ps ax | grep -w " + pName + " | grep -v grep | awk '{print $1}'";
        else command = "jps | grep " + pName + " | awk '{print $1}'";
        hd = TestConfig.getInstance().getMasterDescription()
            .getVmDescription().getHostDescription();
        ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
      /*File log = new File(".");
      pb.redirectErrorStream(true);
      String dest = log.getCanonicalPath() + File.separator + "PIDs_" + HostHelper.getLocalHost() +
          ".log";
      File logFile = new File(dest);*/
        pb.redirectOutput(ProcessBuilder.Redirect.appendTo(logFile));
        pr = pb.start();
        pr.waitFor();
        FileInputStream fis = new FileInputStream(logFile);
        BufferedReader br = new BufferedReader(new InputStreamReader(fis));
        String str = null;
        while ((str = br.readLine()) != null) {
          int pid = Integer.parseInt(str);
          try {
            if (pids.contains(pid)) {
              Log.getLogWriter().info("Pid is already recorded with Master" + pid);
            } else {
              pids.add(pid);
              RemoteTestModule.Master.recordPID(hd, pid);
              SnappyBB.getBB().getSharedMap().put("pid" + "_" + pName + "_" + str, str);
              SnappyBB.getBB().getSharedMap().put("host" + "_" + pid + "_" + HostHelper
                  .getLocalHost(), HostHelper.getLocalHost());
            }
          } catch (RemoteException e) {
            String s = "Unable to access master to record PID: " + pid;
            throw new HydraRuntimeException(s, e);
          }
          Log.getLogWriter().info("pid value successfully recorded with Master");
        }
        br.close();
      }
    } catch (IOException e) {
      String s = "Problem while starting the process : " + pr;
      throw new TestException(s, e);
    } catch (InterruptedException e) {
      String s = "Exception occurred while waiting for the process execution : " + pr;
      throw new TestException(s, e);
    }
  }


  /**
   * Task(ENDTASK) for cleaning up snappy processes, because they are not stopped by Hydra in case of Test failure.
   */
  public static void HydraTask_cleanUpSnappyProcessesOnFailure() {
    Process pr = null;
    ProcessBuilder pb = null;
    File logFile = null, log = null, nukeRunOutput = null;
    try {
      Set<String> pidList = new HashSet<>();
      HostDescription hd = TestConfig.getInstance().getMasterDescription()
          .getVmDescription().getHostDescription();
      pidList = snappyTest.getPidList();
      log = new File("");
      String nukerun = log.getCanonicalPath() + File.separator + "snappyNukeRun.sh";
      logFile = new File(nukerun);
      String nukeRunOutputString = log.getCanonicalPath() + File.separator + "nukeRunOutput.log";
      nukeRunOutput = new File(nukeRunOutputString);
      FileWriter fw = new FileWriter(logFile.getAbsoluteFile(), true);
      BufferedWriter bw = new BufferedWriter(fw);
      for (String pidString : pidList) {
        int pid = Integer.parseInt(pidString);
        String pidHost = snappyTest.getPidHost(Integer.toString(pid));
        if (pidHost.equalsIgnoreCase("localhost")) {
          bw.write("/bin/kill -KILL " + pid);
        } else {
          bw.write("ssh -n -x -o PasswordAuthentication=no -o StrictHostKeyChecking=no " +
              pidHost + " /bin/kill -KILL " + pid);
        }
        bw.newLine();
        try {
          RemoteTestModule.Master.removePID(hd, pid);
        } catch (RemoteException e) {
          String s = "Failed to remove PID from nukerun script: " + pid;
          throw new HydraRuntimeException(s, e);
        }
      }
      bw.close();
      fw.close();
      logFile.setExecutable(true);
      pb = new ProcessBuilder(nukerun);
      pb.redirectErrorStream(true);
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(nukeRunOutput));
      pr = pb.start();
      pr.waitFor();
    } catch (IOException e) {
      throw new TestException("IOException occurred while retriving logFile path " + log + "\nError Message:" + e.getMessage());
    } catch (InterruptedException e) {
      String s = "Exception occurred while waiting for the process execution : " + pr;
      throw new TestException(s, e);
    }
  }

  public String getLeadHost() {
    if (isLongRunningTest) {
      leadHost = getDataFromFile("leadHost");
      if (leadHost == null)
        leadHost = getPrimaryLeadHost();
    } else {
      leadHost = getPrimaryLeadHost();
    }
    return leadHost;
  }

  public String getLeadPort() {
    String leadPort = null;
    if (isLongRunningTest) {
      leadPort = getDataFromFile("leadPort");
      if (leadPort == null)
        leadPort = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadPort");
    } else {
      leadPort = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadPort");
    }
    return leadPort;
  }

  protected String getPrimaryLeadHost() {
    leadHost = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadHost");
    if (leadHost == null) {
      retrievePrimaryLeadHost();
      leadHost = (String) SnappyBB.getBB().getSharedMap().get("primaryLeadHost");
      Log.getLogWriter().info("primaryLead Host is: " + leadHost);
    }
    return leadHost;
  }

  public synchronized void retrievePrimaryLeadHost() {
    Object[] tmpArr = null;
    String leadPort = null;
    tmpArr = getPrimaryLeadVM(cycleLeadVMTarget);
    List<ClientVmInfo> vmList;
    vmList = (List<ClientVmInfo>) (tmpArr[0]);
    Set<String> myDirList = new LinkedHashSet<String>();
    myDirList = getFileContents("logDir_", myDirList);
    for (int i = 0; i < vmList.size(); i++) {
      ClientVmInfo targetVm = vmList.get(i);
      String clientName = targetVm.getClientName();
      for (String vmDir : myDirList) {
        if (vmDir.contains(clientName)) {
          String[] splitedNodeConfig = vmDir.split("_");
          leadHost = splitedNodeConfig[splitedNodeConfig.length - 2];
          Log.getLogWriter().info("New Primary leadHost is: " + leadHost);
          SnappyBB.getBB().getSharedMap().put("primaryLeadHost", leadHost);
          leadPort = getPrimaryLeadPort(clientName);
          SnappyBB.getBB().getSharedMap().put("primaryLeadPort", leadPort);
          if (isLongRunningTest) writeLeadHostPortInfo();
        }
      }
    }
  }

  /*
  * Returns the log file name.  Autogenerates the directory name at runtime
  * using the same path as the master.  The directory is created if needed.
  *
  * @throws HydraRuntimeException if the directory cannot be created.
  */
  private synchronized String getLogDir() {
    if (this.logFile == null) {
      Vector<String> names = TestConfig.tab().vecAt(ClientPrms.gemfireNames);
      String dirname = generateLogDirName();
//            this.localHost = HostHelper.getLocalHost();
      File dir = new File(dirname);
      String fullname = dir.getAbsolutePath();
      try {
        FileUtil.mkdir(dir);
        try {
          for (String name : names) {
            String[] splitedName = name.split("gemfire");
            String newName = splitedName[0] + splitedName[1];
//                        if (newName.equals(RemoteTestModule.getMyClientName())) {
            RemoteTestModule.Master.recordDir(hd,
                name, fullname);
//                        }
          }
        } catch (RemoteException e) {
          String s = "Unable to access master to record directory: " + dir;
          throw new HydraRuntimeException(s, e);
        }
      } catch (VirtualMachineError e) {
        SystemFailure.initiateFailure(e);
        throw e;
      } catch (Error e) {
        String s = "Unable to create directory: " + dir;
        throw new HydraRuntimeException(s);
      }
      this.logFile = dirname;
      log().info("logFile name is " + this.logFile);
    }
    return this.logFile;
  }

  private String generateLogDirName() {
    String dirname = hd.getUserDir() + File.separator
        + "vm_" + RemoteTestModule.getMyVmid()
        + "_" + RemoteTestModule.getMyClientName()
        + "_" + HostHelper.getLocalHost()
        + "_" + RemoteTestModule.getMyPid();
    return dirname;
  }

  protected synchronized void generateConfig(String fileName) {
    File file = null;
    try {
      String path = productConfDirPath + sep + fileName;
      log().info("File Path is ::" + path);
      file = new File(path);

      // if file doesnt exists, then create it
      if (!file.exists()) {
        file.createNewFile();
      } else if (file.exists()) {
        if (isStopMode) return;
        file.setWritable(true);
        //file.delete();
        Files.delete(Paths.get(path));
        Log.getLogWriter().info(fileName + " file deleted");
        file.createNewFile();
      }
    } catch (IOException e) {
      String s = "Problem while creating the file : " + file;
      throw new TestException(s, e);
    }
  }

  /**
   * Deletes the snappy config generated spcific to test run after successful test execution.
   */
  public static void HydraTask_deleteSnappyConfig() throws IOException {
    String locatorConf = productConfDirPath + sep + "locators";
    String serverConf = productConfDirPath + sep + "servers";
    String leadConf = productConfDirPath + sep + "leads";
    Files.delete(Paths.get(locatorConf));
    Log.getLogWriter().info("locators file deleted");
    Files.delete(Paths.get(serverConf));
    Log.getLogWriter().info("servers file deleted");
    Files.delete(Paths.get(leadConf));
    Log.getLogWriter().info("leads file deleted");
    // Removing twitter data directories if exists.
    String twitterdata = dtests + "twitterdata";
    String copiedtwitterdata = dtests + "copiedtwitterdata";
    File file = new File(twitterdata);
    if (file.exists()) {
      file.delete();
      Log.getLogWriter().info("Done removing twitter data directory.");
    }
    file = new File(copiedtwitterdata);
    if (file.exists()) {
      file.delete();
      Log.getLogWriter().info("Done removing copiedtwitterdata data directory.");
    }
  }

  public int getMyTid() {
    int myTid = RemoteTestModule.getCurrentThread().getThreadId();
    return myTid;
  }


  /**
   * Start snappy cluster using snappy-start-all.sh script.
   */
  public static synchronized void HydraTask_startSnappyCluster() {
    if (forceStart) {
      startSnappyCluster();
    } else {
      int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.snappyClusterStarted);
      if (num == 1) {
        startSnappyCluster();
      }
    }
  }

  protected static void startSnappyCluster() {
    File log = null;
    ProcessBuilder pb = null;
    try {
      pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-start-all.sh"));
      log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "snappySystem.log";
      File logFile = new File(dest);
      snappyTest.executeProcess(pb, logFile);
    } catch (IOException e) {
      String s = "problem occurred while retriving destination logFile path " + log;
      throw new TestException(s, e);
    }
  }

  public static synchronized void HydraTask_recordProcessIDWithHost() {
    if (useRowStore) {
      snappyTest.recordSnappyProcessIDinNukeRun("GfxdDistributionLocator");
      snappyTest.recordSnappyProcessIDinNukeRun("GfxdServerLauncher");
    } else {
      snappyTest.recordSnappyProcessIDinNukeRun("LocatorLauncher");
      snappyTest.recordSnappyProcessIDinNukeRun("ServerLauncher");
      snappyTest.recordSnappyProcessIDinNukeRun("LeaderLauncher");
      snappyTest.recordSnappyProcessIDinNukeRun("Worker");
      snappyTest.recordSnappyProcessIDinNukeRun("Master");
    }
  }


  /**
   * Create and start snappy locator using snappy-locators.sh script.
   */
  public static synchronized void HydraTask_createAndStartSnappyLocator() {
    int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.locatorsStarted);
    if (num == 1) {
      snappyTest.startSnappyLocator();
    }
  }


  /**
   * Create and start snappy server.
   */
  public static synchronized void HydraTask_createAndStartSnappyServers() {
    int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.serversStarted);
    if (num == 1) {
      snappyTest.startSnappyServer();
    }
  }


  /**
   * Creates and start snappy lead.
   */
  public static synchronized void HydraTask_createAndStartSnappyLeader() {
    int num = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.leadsStarted);
    if (num == 1) {
      snappyTest.startSnappyLead();
    }
  }

  /**
   * Stops snappy lead.
   */
  public static synchronized void HydraTask_stopSnappyLeader() {
    File log = null;
    try {
      ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-leads.sh"),
          "stop");
      log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "snappyLeaderSystem.log";
      File logFile = new File(dest);
      snappyTest.executeProcess(pb, logFile);
      SnappyBB.getBB().getSharedCounters().zero(SnappyBB.leadsStarted);
    } catch (IOException e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    } catch (Exception e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    }
  }

  /**
   * Stops snappy server/servers.
   */
  public static synchronized void HydraTask_stopSnappyServers() {
    File log = null;
    try {
      ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-servers.sh"), "stop");
      log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "snappyServerSystem.log";
      File logFile = new File(dest);
      snappyTest.executeProcess(pb, logFile);
      SnappyBB.getBB().getSharedCounters().zero(SnappyBB.serversStarted);
    } catch (IOException e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    } catch (Exception e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    }
  }

  /**
   * Stops a snappy locator.
   */
  public static synchronized void HydraTask_stopSnappyLocator() {
    File log = null;
    try {
      ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-locators.sh")
          , "stop");
      log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "snappyLocatorSystem.log";
      File logFile = new File(dest);
      snappyTest.executeProcess(pb, logFile);
      SnappyBB.getBB().getSharedCounters().zero(SnappyBB.locatorsStarted);
    } catch (IOException e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    } catch (Exception e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    }
  }

  public static synchronized void HydraTask_stopSnappyCluster() {
    File log = null;
    try {
      initSnappyArtifacts();
      ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-stop-all.sh"));
      log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "snappySystem.log";
      File logFile = new File(dest);
      snappyTest.executeProcess(pb, logFile);
      boolean stopAllFailed = snappyTest.waitForStopAll();
      if (stopAllFailed) {
        // try again
        Thread.sleep(60000);
        stopAllFailed = snappyTest.waitForStopAll();
        if (stopAllFailed) {
          //threadDumpForAllServers();
        }
      }
      SnappyBB.getBB().getSharedCounters().decrement(SnappyBB.snappyClusterStarted);
    } catch (IOException e) {
      String s = "problem occurred while retriving destination logFile path " + log;
      throw new TestException(s, e);
    } catch (InterruptedException e) {
      String s = "Exception occurred while waiting for snappy-stop-all script execution..";
      throw new TestException(s, e);
    }
  }

  /**
   * Concurrently stops a List of snappy store VMs, then restarts them.  Waits for the
   * restart to complete before returning.
   */
  public static void HydraTask_cycleStoreVms() {
    if (cycleVms) {
      int numToKill = TestConfig.tab().intAt(SnappyPrms.numVMsToStop, 1);
      int stopStartVms = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.stopStartVms);
      Long lastCycledTimeForStoreFromBB = (Long) SnappyBB.getBB().getSharedMap().get(LASTCYCLEDTIME);
      snappyTest.cycleVM(numToKill, stopStartVms, "storeVmCycled", lastCycledTimeForStoreFromBB,
          lastCycledTime, "server", false, false, false);
    }
  }

  /**
   * Concurrently stops a List of snappy locator VMs, then restarts them.  Waits for the
   * restart to complete before returning.
   */
  public static void HydraTask_cycleLocatorVms() {
    if (cycleVms) {
      int numToKill = TestConfig.tab().intAt(SnappyPrms.numVMsToStop, 1);
      int stopStartVms = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.stopStartVms);
      Long lastCycledTimeForLocatorFromBB = (Long) SnappyBB.getBB().getSharedMap().get
          (LASTCYCLEDTIMEFORLOCATOR);
      snappyTest.cycleVM(numToKill, stopStartVms, "locatorVmCycled", lastCycledTimeForLocatorFromBB,
          lastCycledTime, "locator", false, false, false);
    }
  }

  /**
   * Stops snappy primary lead member, then restarts it.  Waits for the
   * restart to complete before returning.
   */
  public static synchronized void HydraTask_cycleLeadVM() {
    if (cycleVms) {
      int numToKill = TestConfig.tab().intAt(SnappyPrms.numLeadsToStop, 1);
      int stopStartVms = (int) SnappyBB.getBB().getSharedCounters().incrementAndRead(SnappyBB.stopStartLeadVms);
      Long lastCycledTimeForLeadFromBB = (Long) SnappyBB.getBB().getSharedMap().get(LASTCYCLEDTIMEFORLEAD);
      snappyTest.cycleVM(numToKill, stopStartVms, "leadVmCycled", lastCycledTimeForLeadFromBB,
          lastCycledTimeForLead, "lead", false, false, false);
    }
  }

  protected void
  cycleVM(int numToKill, int stopStartVMs, String cycledVM, Long lastCycledTimeFromBB, long
      lastCycledTime, String vmName, boolean isDmlOp, boolean restart, boolean rebalance) {
    if (!cycleVms) {
      Log.getLogWriter().warning("cycleVms sets to false, no node will be brought down in the test run");
      return;
    }
    List<ClientVmInfo> vms = null;
    if (stopStartVMs == 1) {
      Object vmCycled = SnappyBB.getBB().getSharedMap().get(cycledVM);
      if (vmCycled == null) {
        while (true) {
          try {
            if (vmName.equalsIgnoreCase("lead"))
              vms = stopStartVMs(numToKill, "lead", isDmlOp, restart, rebalance);
            else if (vmName.equalsIgnoreCase("server")) vms = stopStartVMs(numToKill, "server",
                isDmlOp, restart, rebalance);
            else if (vmName.equalsIgnoreCase("locator")) vms = stopStartVMs(numToKill,
                "locator", isDmlOp, restart, rebalance);
            break;
          } catch (TestException te) {
            throw te;
          }
        }
      } //first time
      else {
        //relaxing a little for HA tests
        //using the BB to track when to kill the next set of vms
        if (lastCycledTimeFromBB == null) {
          int sleepMS = 20000;
          Log.getLogWriter().info("allow  " + sleepMS / 1000 + " seconds before killing others");
          MasterController.sleepForMs(sleepMS); //no vms has been cycled before
        } else if (lastCycledTimeFromBB > lastCycledTime) {
          lastCycledTime = lastCycledTimeFromBB;
          log().info("update last cycled lead vm is set to " + lastCycledTime);
        }

        if (lastCycledTime != 0) {
          long currentTime = System.currentTimeMillis();
          if (currentTime - lastCycledTime < waitTimeBeforeNextCycleVM * THOUSAND) {
            if (vmName.equalsIgnoreCase("lead"))
              SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartLeadVms);
            else if (vmName.equalsIgnoreCase("server")) SnappyBB.getBB().getSharedCounters().zero
                (SnappyBB.stopStartVms);
            else if (vmName.equalsIgnoreCase("locator")) SnappyBB.getBB().getSharedCounters().zero
                (SnappyBB.stopStartLocatorVms);
            return;
          } else {
            if (vmName.equalsIgnoreCase("lead")) log().info("cycle lead vm starts at: " +
                currentTime);
            else if (vmName.equalsIgnoreCase("server")) log().info("cycle store vm starts at: " +
                currentTime);
            else if (vmName.equalsIgnoreCase("locator")) log().info("cycle locator vm starts " +
                "at:" + currentTime);
          }
        }
        vms = stopStartVMs(numToKill, vmName, isDmlOp, restart, rebalance);
      }
      if (vms == null || vms.size() == 0) {
        if (vmName.equalsIgnoreCase("lead")) {
          Log.getLogWriter().info("No lead vm being chosen to be stopped");
          SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartLeadVms);
        } else if (vmName.equalsIgnoreCase("server")) {
          Log.getLogWriter().info("No store vm being chosen to be stopped");
          SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartVms);
        } else if (vmName.equalsIgnoreCase("locator")) {
          Log.getLogWriter().info("No locator vm being chosen to be stopped");
          SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartLocatorVms);
        }
        return;
      }
//            Log.getLogWriter().info("Total number of PR is " + numOfPRs);
//            if (numOfPRs > 0)
//                PRObserver.waitForRebalRecov(vms, 1, numOfPRs, null, null, false);
      long currentTime = System.currentTimeMillis();
      if (vmName.equalsIgnoreCase("lead")) {
        log().info("cycle lead vm finishes at: " + currentTime);
        SnappyBB.getBB().getSharedMap().put(LASTCYCLEDTIMEFORLEAD, currentTime);
        SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartLeadVms);
      } else if (vmName.equalsIgnoreCase("server")) {
        log().info("cycle store vm finishes at: " + currentTime);
        SnappyBB.getBB().getSharedMap().put(LASTCYCLEDTIME, currentTime);
        SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartVms);
      } else if (vmName.equalsIgnoreCase("locator")) {
        log().info("cycle locator vm finishes at: " + currentTime);
        SnappyBB.getBB().getSharedMap().put(LASTCYCLEDTIMEFORLOCATOR, currentTime);
        SnappyBB.getBB().getSharedCounters().zero(SnappyBB.stopStartVms);
      }
      SnappyBB.getBB().getSharedMap().put(cycledVM, "true");
    }
  }

  protected List<ClientVmInfo> stopStartVMs(int numToKill, String vmName, boolean isDmlOp,
                                            boolean restart, boolean rebalance) {
    if (vmName.equalsIgnoreCase("lead")) {
      log().info("stopStartVMs : cycle lead vm starts at: " + System.currentTimeMillis());
      return stopStartVMs(numToKill, cycleLeadVMTarget, vmName, isDmlOp, restart, rebalance);
    } else if (vmName.equalsIgnoreCase("server")) {
      log().info("stopStartVMs : cycle store vm starts at: " + System.currentTimeMillis());
      return stopStartVMs(numToKill, cycleVMTarget, vmName, isDmlOp, restart, rebalance);
    } else if (vmName.equalsIgnoreCase("locator"))
      log().info("stopStartVMs : cycle store vm starts at: " + System.currentTimeMillis());
    return stopStartVMs(numToKill, cycleLocatorVMTarget, vmName, isDmlOp, restart, rebalance);
  }

  protected List<ClientVmInfo> stopStartLeadVM(int numToKill) {
    log().info("cycle lead vm starts at: " + System.currentTimeMillis());
    return stopStartVMs(numToKill, cycleLeadVMTarget, "lead", false, false, false);
  }

  @SuppressWarnings("unchecked")
  protected List<ClientVmInfo> stopStartVMs(int numToKill, String target, String vmName, boolean
      isDmlOp, boolean restart, boolean rebalance) {
    Object[] tmpArr = null;
    if (vmName.equalsIgnoreCase("lead")) tmpArr = snappyTest.getPrimaryLeadVMWithHA(target);
    else tmpArr = StopStartVMs.getOtherVMs(numToKill, target);
    // get the VMs to stop; vmList and stopModeList are parallel lists

    Object vm1 = SnappyBB.getBB().getSharedMap().get("storeVMTarget1");
    Object vm2 = SnappyBB.getBB().getSharedMap().get("storeVMTarget2");
    List<ClientVmInfo> vmList;
    List<String> stopModeList;

    if (vm1 == null && vm2 == null) {
      vmList = (List<ClientVmInfo>) (tmpArr[0]);
      stopModeList = (List<String>) (tmpArr[1]);
      for (ClientVmInfo client : vmList) {
        PRObserver.initialize(client.getVmid());
      } //clear bb info for the vms to be stopped/started
    } else {
      vmList = (List<ClientVmInfo>) (tmpArr[0]);
      stopModeList = (List<String>) (tmpArr[1]);
      for (int i = 0; i < vmList.size(); i++) {
        if (vmList.get(i).getVmid().intValue() == ((ClientVmInfo) vm1).getVmid().intValue()
            || vmList.get(i).getVmid().intValue() == ((ClientVmInfo) vm2).getVmid().intValue()) {
          Log.getLogWriter().info("remove the vm " + vmList.get(i).getVmid() + " from the stop list");
          vmList.remove(i);
        } else PRObserver.initialize(vmList.get(i).getVmid());
      }//clear bb info for the vms to be stopped/started
    }
    if (vmList.size() != 0) {
      stopStartVMs(vmList, stopModeList, vmName, isDmlOp, restart, rebalance);
    }
    return vmList;
  }

  protected void stopStartVMs(List<ClientVmInfo> vmList, List<String> stopModeList, String
      vmName, boolean isDmlOp, boolean restart, boolean rebalance) {
    Set<String> myDirList = new LinkedHashSet<String>();
    myDirList = getFileContents("logDir_", myDirList);
    if (vmList.size() != stopModeList.size()) {
      throw new TestException("Expected targetVmList " + vmList + " and stopModeList " +
          stopModeList + " to be parallel lists of the same size, but they have different sizes");
    }
    Log.getLogWriter().info("In stopStartVMs, vms to stop: " + vmList +
        ", corresponding stop modes: " + stopModeList);
    for (int i = 0; i < vmList.size(); i++) {
      ClientVmInfo targetVm = (ClientVmInfo) (vmList.get(i));
      String stopMode = stopModeList.get(i);
      String clientName = targetVm.getClientName();
      for (String vmDir : myDirList) {
        if (vmDir.contains(clientName)) {
          recycleVM(vmDir, stopMode, clientName, vmName, isDmlOp, restart, rebalance);
        }
      }
    }
  }

  protected void recycleVM(String vmDir, String stopMode, String clientName, String vmName,
                           boolean isDmlOp, boolean restart, boolean rebalance) {
    if (!isDmlOp && !restart && !rebalance && (stopMode.equalsIgnoreCase("NiceKill") ||
        stopMode.equalsIgnoreCase("NICE_KILL"))) {
      killVM(vmDir, clientName, vmName);
      try {
        Thread.sleep(180000);
      } catch (InterruptedException e) {
        String s = "Exception occurred while waiting for the kill " + clientName + "process " +
            "execution..";
        throw new TestException(s, e);
      }
      startVM(vmDir, clientName, vmName);
    }
  }

  protected void killVM(String vmDir, String clientName, String vmName) {
    if (vmName.equalsIgnoreCase("lead")) {
      regenerateConfigData(vmDir, "leads", clientName, vmName);
      HydraTask_stopSnappyLeader();
    } else if (vmName.equalsIgnoreCase("server")) {
      regenerateConfigData(vmDir, "servers", clientName, vmName);
      HydraTask_stopSnappyServers();
    } else if (vmName.equalsIgnoreCase("locator")) {
      regenerateConfigData(vmDir, "locators", clientName, vmName);
      HydraTask_stopSnappyLocator();
    }
    try {
      Thread.sleep(10000);
      boolean serverStopFailed = snappyTest.waitForMemberStop(vmDir, clientName, vmName);
      if (serverStopFailed) {
        // try again
        Thread.sleep(60000);
        serverStopFailed = snappyTest.waitForMemberStop(vmDir, clientName, vmName);
        if (serverStopFailed) {
          // last try
          Thread.sleep(120000);
          serverStopFailed = snappyTest.waitForMemberStop(vmDir, clientName, vmName);
          if (serverStopFailed) {
            //threadDumpForAllServers();
          }
        }
      }
    } catch (InterruptedException e) {
      String s = "Exception occurred while waiting for the kill " + clientName + "process " +
          "execution..";
      throw new TestException(s, e);
    }
    Log.getLogWriter().info(clientName + " stopped successfully...");
  }

  /*
  protected static void threadDumpForAllServers() {
    Set<String> pidList;
    Process pr = null;
    ProcessBuilder pb;
    File logFile, log = null, serverHeapDumpOutput;
    try {
      HostDescription hd = TestConfig.getInstance().getMasterDescription()
          .getVmDescription().getHostDescription();
      pidList = SnappyStartUpTest.getServerPidList();
      pidList.addAll(SnappyStartUpTest.getLeaderPidList());
      log = new File(".");
      String server = log.getCanonicalPath() + File.separator + "threadDumpAllServers.sh";
      logFile = new File(server);
      String serverKillLog = log.getCanonicalPath() + File.separator +
          "serversThreadDumpStopFailure.log";
      serverHeapDumpOutput = new File(serverKillLog);
      FileWriter fw = new FileWriter(logFile.getAbsoluteFile(), true);
      BufferedWriter bw = new BufferedWriter(fw);
      List asList = new ArrayList(pidList);
      Collections.shuffle(asList);
      for (String pidString : pidList) {
        int pid = Integer.parseInt(pidString);
        String pidHost = snappyTest.getPidHost(Integer.toString(pid));
        if (pidHost.equalsIgnoreCase("localhost")) {
          bw.write("kill -23 " + pid);
          bw.newLine();
          bw.write("sleep 4;");
          bw.newLine();
          bw.write("kill -3 " + pid);
        } else {
          bw.write("ssh -n -x -o PasswordAuthentication=no -o StrictHostKeyChecking=no " +
              pidHost + " kill -23 " + pid);
          bw.newLine();
          bw.write("sleep 4;");
          bw.newLine();
          bw.write("ssh -n -x -o PasswordAuthentication=no -o StrictHostKeyChecking=no " +
              pidHost + " kill -3 " + pid);
        }
        bw.newLine();
      }
      bw.close();
      fw.close();
      logFile.setExecutable(true);
      pb = new ProcessBuilder(server);
      pb.redirectErrorStream(true);
      pb.redirectOutput(ProcessBuilder.Redirect.appendTo(serverHeapDumpOutput));
      pr = pb.start();
      pr.waitFor();
    } catch (IOException e) {
      throw new TestException("IOException occurred while retriving logFile path " + log + "\nError Message:" + e.getMessage());
    } catch (InterruptedException e) {
      String s = "Exception occurred while waiting for the process execution : " + pr;
      throw new TestException(s, e);
    }
  }
  */

  protected boolean waitForMemberStop(String vmDir, String clientName, String vmName) {
    File commandOutput;
    try {
      File log = new File("");
      String dest = log.getCanonicalPath() + File.separator + clientName + "Status_" +
          RemoteTestModule.getCurrentThread().getThreadId() + "_" + System.currentTimeMillis() + ".log";
      commandOutput = new File(dest);
      String expression;
      String scriptName = null;
      if (vmName.equalsIgnoreCase("server")) {
        scriptName = "snappy-server.sh";
      } else if (vmName.equalsIgnoreCase("lead")) {
        scriptName = "snappy-lead.sh";
      } else if (vmName.equalsIgnoreCase("locator")) {
        scriptName = "snappy-locator.sh";
      }
      expression = snappyTest.getScriptLocation(scriptName) + " status " +
          " -dir=" + vmDir + " > " + commandOutput + " 2>&1 ; grep -e 'status: stopped' -e " +
          "'status: waiting' -e 'status: stopping' -e 'java.lang.IllegalStateException' " +
          commandOutput + " | wc -l)\"";
      String command = "while [ \"$(" + expression + " -le  0  ] ; do rm " +
          commandOutput + " ;  touch " + commandOutput + "   ;  sleep " +
          SnappyPrms.getSleepTimeSecsForMemberStatus() + " ; done";
      ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
      Log.getLogWriter().info("Server Status script for " + clientName + " starts at: " + System
          .currentTimeMillis());
      // output is already redirected by script
      executeProcess(pb, null);
      Log.getLogWriter().info("Server Status script for " + clientName + " finishes at:  " + System
          .currentTimeMillis());
    } catch (IOException e) {
      String s = "Problem while reading the file : " + logFile;
      throw new TestException(s, e);
    }
    return executeStatusTask(commandOutput);
  }

  protected boolean executeStatusTask(File commandOutput) {
    boolean found = false;
    try {
      FileInputStream fis = new FileInputStream(commandOutput);
      BufferedReader br = new BufferedReader(new InputStreamReader(fis));
      String line;
      String searchString1 = "java.lang.IllegalStateException";
      String searchString2 = "status: stopping";
      String searchString3 = "status: waiting";
      while ((line = br.readLine()) != null && !found) {
        if (line.toLowerCase().contains(searchString1.toLowerCase()) || line.toLowerCase().contains
            (searchString2.toLowerCase()) || line.toLowerCase().contains(searchString3.toLowerCase
            ())) {
          found = true;
          Log.getLogWriter().info("member did not stop successfully...");
        }
      }
      br.close();
    } catch (FileNotFoundException e) {
      String s = "Unable to find file: " + logFile;
      throw new TestException(s);
    } catch (IOException e) {
      String s = "Problem while reading the file : " + logFile;
      throw new TestException(s, e);
    }
    return found;
  }

  protected boolean waitForStopAll() {
    File commandOutput;
    try {
      File log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "stopAllStatus_" +
          RemoteTestModule.getCurrentThread().getThreadId() + "_" + System.currentTimeMillis() + ".log";
      commandOutput = new File(dest);
      String expression = snappyTest.getScriptLocation("snappy-status-all.sh")
          + " > " + commandOutput + " 2>&1 ; grep -e 'status: stopped' -e " +
          "'status: waiting' -e 'status: stopping' -e 'java.lang.IllegalStateException' " +
          commandOutput + " | wc -l)\"";
      String command = "while [ \"$(" + expression + " -le  0  ] ; do sleep " +
          SnappyPrms.getSleepTimeSecsForMemberStatus() + " ; done";
      ProcessBuilder pb = new ProcessBuilder("/bin/bash", "-c", command);
      Log.getLogWriter().info("snappy-status-all script starts at: " +
          System.currentTimeMillis());
      // output is already redirected by script
      executeProcess(pb, null);
      Log.getLogWriter().info("snappy-status-all script finishes at:  " + System
          .currentTimeMillis());
    } catch (IOException e) {
      String s = "Problem while reading the file : " + logFile;
      throw new TestException(s, e);
    }
    return executeStatusTask(commandOutput);
  }

  protected void startVM(String vmDir, String clientName, String vmName) {
    if (vmName.equalsIgnoreCase("lead")) {
      //regenerateConfigData(vmDir, "leads", clientName, vmName);
      startSnappyLead();
    } else if (vmName.equalsIgnoreCase("server")) {
      //regenerateConfigData(vmDir, "servers", clientName, vmName);
      startSnappyServer();
    } else if (vmName.equalsIgnoreCase("locator")) {
      //regenerateConfigData(vmDir, "locators", clientName, vmName);
      startSnappyLocator();
    }
    Log.getLogWriter().info(clientName + " restarted successfully...");
  }

  public void regenerateConfigData(String vmDir, String confFileName, String clientName, String
      vmName) {
    generateConfig(confFileName);
    Set<String> fileContent = new LinkedHashSet<String>();
    if (vmName.equalsIgnoreCase("lead")) {
      if (isLongRunningTest) writeLeadHostPortInfo();
      fileContent = snappyTest.getFileContents("leadLogDir", fileContent);
    } else if (vmName.equalsIgnoreCase("server")) {
      fileContent = snappyTest.getFileContents("serverLogDir", fileContent);
    } else if (vmName.equalsIgnoreCase("locator")) {
      fileContent = snappyTest.getFileContents("locatorLogDir", fileContent);
    }
    for (String nodeConfig : fileContent) {
      if (nodeConfig.contains(vmDir)) {
        writeNodeConfigData(confFileName, nodeConfig, true);
      }
    }
  }

  public static Object[] getPrimaryLeadVMWithHA(String clientMatchStr) {
    ArrayList vmList = new ArrayList();
    ArrayList stopModeList = new ArrayList();
    int myVmID = RemoteTestModule.getMyVmid();
    // get VMs that contain the clientMatchStr
    List vmInfoList = StopStartVMs.getAllVMs();
    vmInfoList = StopStartVMs.getMatchVMs(vmInfoList, clientMatchStr);
    // now all vms in vmInfoList match the clientMatchStr
    do {
      Object[] tmpArr = getClientVmInfo(vmInfoList, clientMatchStr);
      ClientVmInfo info = (ClientVmInfo) tmpArr[0];
      int randInt = (int) tmpArr[1];
      if (info.getVmid().intValue() != myVmID) { // info is not the current VM
        getLeadVM(info, vmList, stopModeList);
      }
      vmInfoList.remove(randInt);
    } while (vmList.size() < vmInfoList.size());
    return new Object[]{vmList, stopModeList, vmInfoList};
  }

  public static Object[] getPrimaryLeadVM(String clientMatchStr) {
    ArrayList vmList = new ArrayList();
    ArrayList stopModeList = new ArrayList();
    // get VMs that contain the clientMatchStr
    List vmInfoList = StopStartVMs.getAllVMs();
    vmInfoList = StopStartVMs.getMatchVMs(vmInfoList, clientMatchStr);
    // now all vms in vmInfoList match the clientMatchStr
    do {
      Object[] tmpArr = getClientVmInfo(vmInfoList, clientMatchStr);
      ClientVmInfo info = (ClientVmInfo) tmpArr[0];
      int randInt = (int) tmpArr[1];
      getLeadVM(info, vmList, stopModeList);
      vmInfoList.remove(randInt);
    } while (vmList.size() < vmInfoList.size());
    return new Object[]{vmList, stopModeList, vmInfoList};
  }

  protected static Object[] getClientVmInfo(List vmInfoList, String clientMatchStr) {
    if (vmInfoList.size() == 0) {
      throw new TestException("Unable to find lead node " +
          " vms to stop with client match string " + clientMatchStr +
          "; either a test problem or add StopStartVMs.StopStart_initTask to the test");
    }
    // add a VmId to the list of vms to stop
    int randInt = TestConfig.tab().getRandGen().nextInt(0, vmInfoList.size() - 1);
    ClientVmInfo info = (ClientVmInfo) (vmInfoList.get(randInt));
    return new Object[]{info, randInt};
  }

  protected static void getLeadVM(ClientVmInfo info, ArrayList vmList, ArrayList stopModeList) {
    Set<String> myDirList = new LinkedHashSet<String>();
    myDirList = getFileContents("logDir_", myDirList);
    String vmDir = null;
    String clientName = info.getClientName();
    for (String dir : myDirList) {
      if (dir.contains(clientName)) {
        vmDir = dir;
        break;
      }
    }
    Set<String> fileContent = new LinkedHashSet<String>();
    fileContent = snappyTest.getFileContents("leadLogDir", fileContent);
    boolean found = false;
    for (String nodeConfig : fileContent) {
      if (nodeConfig.contains(vmDir)) {
        //check for active lead member dir
        String searchString1 = "Primary lead lock acquired";
        String searchString2 = "Resuming startup sequence from STANDBY";
        File dirFile = new File(vmDir);
        for (File srcFile : dirFile.listFiles()) {
          if (srcFile.getAbsolutePath().contains("snappyleader.log")) {
            try {
              FileInputStream fis = new FileInputStream(srcFile);
              BufferedReader br = new BufferedReader(new InputStreamReader(fis));
              String str = null;
              while ((str = br.readLine()) != null && !found) {
                if (str.toLowerCase().contains(searchString1.toLowerCase()) || str.toLowerCase().contains(searchString2.toLowerCase())) {
                  found = true;
                }
              }
              br.close();
            } catch (FileNotFoundException e) {
              String s = "Unable to find file: " + srcFile.getAbsolutePath();
              throw new TestException(s);
            } catch (IOException e) {
              String s = "Problem while reading the file : " + srcFile.getAbsolutePath();
              throw new TestException(s, e);
            }
          }
        }
      }
    }
    if (found) {
      vmList.add(info);
      // choose a stopMode
      String choice = TestConfig.tab().stringAt(StopStartPrms.stopModes, "NICE_KILL");
      stopModeList.add(choice);
    }
  }

  protected void startSnappyLocator() {
    File log = null;
    ProcessBuilder pb = null;
    try {
      if (useRowStore) {
        Log.getLogWriter().info("Starting locator/s using rowstore option...");
        pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-locators.sh"), "start", "rowstore");
      } else {
        pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-locators.sh"), "start");
      }
      log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "snappyLocatorSystem.log";
      File logFile = new File(dest);
      snappyTest.executeProcess(pb, logFile);
    } catch (IOException e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    }
  }

  protected void startSnappyServer() {
    File log = null;
    ProcessBuilder pb = null;
    try {
      if (useRowStore) {
        Log.getLogWriter().info("Starting server/s using rowstore option...");
        pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-servers.sh"), "start", "rowstore");
      } else {
        pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-servers.sh"), "start");
      }
      log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "snappyServerSystem.log";
      File logFile = new File(dest);
      snappyTest.executeProcess(pb, logFile);
      boolean serverStartFailed = snappyTest.executeStatusTask(logFile);
      if (serverStartFailed) {
        //snappyTest.threadDumpForAllServers();
        /*Thread.sleep(60000);
        startSnappyServer();*/
      }
    } catch (IOException e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    }
  }

  protected void startSnappyLead() {
    File log = null;
    try {
      ProcessBuilder pb = new ProcessBuilder(snappyTest.getScriptLocation("snappy-leads.sh"),
          "start");
      log = new File("");
      String dest = log.getCanonicalPath() + File.separator + "snappyLeaderSystem.log";
      File logFile = new File(dest);
      snappyTest.executeProcess(pb, logFile);
    } catch (IOException e) {
      String s = "problem occurred while retriving logFile path " + log;
      throw new TestException(s, e);
    }
  }

  protected LogWriter log() {
    return Log.getLogWriter();
  }

}
