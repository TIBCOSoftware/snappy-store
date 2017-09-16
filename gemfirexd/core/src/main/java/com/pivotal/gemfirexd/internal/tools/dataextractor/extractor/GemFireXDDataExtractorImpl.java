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
package com.pivotal.gemfirexd.internal.tools.dataextractor.extractor;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.MemoryUsage;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;
import java.util.Scanner;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.FutureTask;
import java.util.logging.FileHandler;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import org.apache.commons.io.FileSystemUtils;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

import com.gemstone.gemfire.cache.DiskAccessException;
import com.gemstone.gemfire.distributed.DistributedSystem;
import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.lang.StringUtils;
import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.FabricService;
import com.pivotal.gemfirexd.FabricServiceManager;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RowFormatter;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.DataDictionary;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.SchemaDescriptor;
import com.pivotal.gemfirexd.internal.iapi.sql.dictionary.TableDescriptor;
import com.pivotal.gemfirexd.internal.iapi.store.access.TransactionController;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerHandle;
import com.pivotal.gemfirexd.internal.iapi.store.raw.ContainerKey;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.tools.dataextractor.diskstore.GFXDDiskStoreImpl;
import com.pivotal.gemfirexd.internal.tools.dataextractor.domain.ServerInfo;
import com.pivotal.gemfirexd.internal.tools.dataextractor.help.HelpStrings;
import com.pivotal.gemfirexd.internal.tools.dataextractor.report.ReportGenerator;
import com.pivotal.gemfirexd.internal.tools.dataextractor.snapshot.GFXDSnapshotExportStat;
import com.pivotal.gemfirexd.internal.tools.dataextractor.snapshot.GFXDSnapshotExporter;
import com.pivotal.gemfirexd.internal.tools.dataextractor.utils.ExtractorUtils;

/***
 * @author bansods
 * @author jhuynh
 */
public class GemFireXDDataExtractorImpl {

  //Command line arguments and options
  public static final String PROPERTY_FILE_ARG = "property-file";
  public static final String USE_DDL_OPT = "--use-ddl-file";
  public static final String HELP_OPT = "--help";
  public static final String LOG_LEVEL_OPT = "--log-level";
  public static final String LOG_FILE_OPT = "--log-file";
  public static final String STRING_DELIMITER = "--string-delimiter";
  public static final String EXTRACT_IN_SERVER_OPT = "--save-in-server-working-dir"; 
  public static final String EXTRACTOR_OUTPUT_DIR_OPT = "--output-dir";
  public static final String USER_NAME_OPT = "--user-name";
  public static final String NUM_THREADS_OPT = "--num-threads";
  public static final String DEFAULT_STRING_DELIMITER = "\"";

  public static final String DEFAULT_SALVAGER_LOG_FILE = "extractor.log";
  public static final String DRIVER_STRING = "io.snappydata.jdbc.EmbeddedDriver";
  public static final String LOGGER_NAME = "extractor.logger";

  public volatile static Logger logger = Logger.getLogger(LOGGER_NAME);;
  protected static FileHandler logFileHandler;

  protected Connection jdbcConn = null;
  private Driver driver;
  private Map<String, List<GFXDSnapshotExportStat>> hostToStatsMap;
  private Map<ServerInfo, List<GFXDSnapshotExportStat>> hostToDdlMap; 

  private List<List<GFXDSnapshotExportStat>> rankedAndGroupedDdlStats;

  private boolean useSingleDDL = false;
  private boolean extractInServerDir = false;
  private String logFilePath = null;
  private String logLevelString = Level.INFO.toString();
  private String propFilePath = null;
  private boolean showHelp = false;
  private String singleDdlFilePath = null;
  private String userName;
  private boolean userOverrideNumThreads = false;
  private int userNumThreads = 0;

  /******
   * Map that holds the command line properties
   */
  protected final Properties extractorProperties = new Properties();

  private String stringDelimiter = DEFAULT_STRING_DELIMITER;
  private String extractedFilesDirPrefix = "EXTRACTED_FILES";
  private File outputDirectory = null;
  private String outputDirectoryPath = null;

  private static List<String> reportableErrors = Collections.synchronizedList(new ArrayList<String>());


  public GemFireXDDataExtractorImpl() {
    hostToStatsMap = new ConcurrentHashMap<String, List<GFXDSnapshotExportStat>>();
    hostToDdlMap = new ConcurrentHashMap<ServerInfo, List<GFXDSnapshotExportStat>>();

    //Initialize and the set the defaults for the extractor properties 
    extractorProperties.setProperty(USE_DDL_OPT, "");
    extractorProperties.setProperty(PROPERTY_FILE_ARG, "");
    extractorProperties.setProperty(HELP_OPT, Boolean.toString(false));
    extractorProperties.setProperty(EXTRACTOR_OUTPUT_DIR_OPT, "");
    extractorProperties.setProperty(STRING_DELIMITER, DEFAULT_STRING_DELIMITER);
    extractorProperties.setProperty(LOG_LEVEL_OPT, Level.INFO.toString());
    extractorProperties.setProperty(LOG_FILE_OPT, DEFAULT_SALVAGER_LOG_FILE);
    extractorProperties.setProperty(EXTRACTOR_OUTPUT_DIR_OPT, System.getProperty("user.dir"));
    extractorProperties.setProperty(USER_NAME_OPT, "");
    extractorProperties.setProperty(NUM_THREADS_OPT, "");
  }

  public GemFireXDDataExtractorImpl(String[] args) {
    this();
  }

  protected void printHelp() throws IOException {
    System.out.println(HelpStrings.helpText);
  }

  protected void configureLogger() throws SecurityException, IOException {
    if (logger == null) {
      logger = Logger.getLogger(LOGGER_NAME);
    }
    logger.setUseParentHandlers(false);
    logFileHandler = new FileHandler(logFilePath);
    logFileHandler.setFormatter(new SimpleFormatter());
    Level logLevel = Level.INFO;
    try {
      logLevel = Level.parse(logLevelString);
    } catch (IllegalArgumentException e) {
      logInfo("Unrecognized log level :" + logLevelString + " defaulting to :" + logLevel);
    }
    logFileHandler.setLevel(logLevel);
    logger.addHandler(logFileHandler);
  }

  public void consumeProperties() throws IOException {
    this.propFilePath = extractorProperties.getProperty(PROPERTY_FILE_ARG);
    this.outputDirectoryPath = extractorProperties.getProperty(EXTRACTOR_OUTPUT_DIR_OPT);
    this.logFilePath = FilenameUtils.concat(getOutputDirectory() ,extractorProperties.getProperty(LOG_FILE_OPT, DEFAULT_SALVAGER_LOG_FILE));
    this.logLevelString = extractorProperties.getProperty(LOG_LEVEL_OPT, Level.INFO.getName());
    this.stringDelimiter = extractorProperties.getProperty(STRING_DELIMITER);
    this.extractInServerDir = Boolean.valueOf(extractorProperties.getProperty(EXTRACT_IN_SERVER_OPT));
    this.showHelp = Boolean.valueOf(extractorProperties.getProperty(HELP_OPT));
    String ddlFilePath = extractorProperties.getProperty(USE_DDL_OPT);
    if (ddlFilePath != null && !ddlFilePath.isEmpty()) {
      this.useSingleDDL = true;
    }
    this.userName = extractorProperties.getProperty(USER_NAME_OPT);

    String numThreads = extractorProperties.getProperty(NUM_THREADS_OPT);
    if (StringUtils.isBlank(numThreads)) {
      this.userOverrideNumThreads = false;
    } else {
      int userNumThreads = -1; 
      try {
        userNumThreads = Integer.valueOf(numThreads);
        this.userNumThreads = userNumThreads;
        this.userOverrideNumThreads = true;
        if (this.userNumThreads < 1) {
          this.userNumThreads = 1;
        }
      } catch (NumberFormatException nfe) {
        System.out.println("Invalid value for " + NUM_THREADS_OPT);
        this.userOverrideNumThreads = false;
      }
    }
  }

  /*******
   * The method which does carries out the extraction process, this method does the following
   * 1) Parse the properties file , creating the List<ServerInfo>
   * 2) Extract the DDL's for each of those server
   * 3) Replay the DDL for the server and extract the data from their respective diskstores
   * 4) Generate the report and summary 
   * @throws Exception
   */
  public void extract() throws Exception {
    long startTime = System.currentTimeMillis();

    if (showHelp) {
      printHelp();
      return;
    }

    logInfo("Reading the properties file : " + propFilePath);
    Map<String, ServerInfo> serverInfoMap = ServerInfo.createServerInfoList(propFilePath);
    /****
     * Check the disk space
     */
    if (!extractInServerDir) {
      if (!ExtractorUtils.checkDiskSpaceInTargetDirectory(serverInfoMap, getOutputDirectory())) {
        return;
      }
    }

    if (useSingleDDL) {
      try {
        List<String> ddlStatements = ExtractorUtils.readSqlStatements(singleDdlFilePath);
        boolean enableOffHeap = isOffHeapUsed(ddlStatements);
        createConnection(enableOffHeap);
        ExtractorUtils.executeDdlFromSqlFile((EmbedConnection)jdbcConn, ddlStatements);
      } catch (Exception e) {
        String exceptionMessage = "Exception occurred while replaying the DDL from file : " + singleDdlFilePath;
        reportableErrors.add(exceptionMessage);
        throw new Exception(exceptionMessage, e);
      }
    }
    else {
      createConnection(false);
    }
    List<DiskStoreImpl> defaultAndDataDictionaryDiskStores = listDiskStores(true);

    //salvage ddl
    extractDDLs(serverInfoMap);
    //sort hostToDDLStats map
    //loop through the groupings
    //Rank the DDL's 

    //Stop the server after DDL extraction, 
    //This is done as we don't know if the DDL contain any offheap tables or not. 
    //The server is stopped, the DDL's are scanned. 
    //If we find offheap tables , We start the server with offheap memory.
    if (!useSingleDDL) {
      stopServer();
      //clean up disk store files so that the next salvage won't have conflicts creating disk stores again
      ExtractorUtils.cleanDiskStores(defaultAndDataDictionaryDiskStores, true);
      if (hostToDdlMap.isEmpty()) {
        logSevere("Unable to extract the schema for any server(s). Unable to continue data extraction");
        return;
      }
    }

    this.rankedAndGroupedDdlStats =  ReportGenerator.rankAndGroupDdlStats(hostToDdlMap);

    Iterator<List<GFXDSnapshotExportStat>> ddlGroupIterator = rankedAndGroupedDdlStats.iterator();

    while (ddlGroupIterator.hasNext()) {
      List<GFXDSnapshotExportStat> serverStatGroup = ddlGroupIterator.next();
      //Picks first sql file for this group as all are the same.
      //String sqlFile = hostToDdlMap.get(serverInfoGroup.get(0)).get(0).getFileName();
      String sqlFile = serverStatGroup.get(0).getFileName();
      List<String> ddlStatements = ExtractorUtils.readSqlStatements(sqlFile);

      //Check the DDL's to see if offheap is enabled or not
      //accordingly start the loner VM
      if (!useSingleDDL) {
        boolean enableOffHeap = isOffHeapUsed(ddlStatements);
        createConnection(enableOffHeap);
      }

      ExtractorUtils.executeDdlFromSqlFile(jdbcConn, ddlStatements);

      List<ServerInfo> serverInfoGroup = new ArrayList<ServerInfo>();

      for (GFXDSnapshotExportStat serverStat : serverStatGroup) {
        serverInfoGroup.add(serverInfoMap.get(serverStat.getServerName()));
      }

      //printTables();
      //retrieve and store all row formatters
      retrieveAllRowFormatters();

      List<DiskStoreImpl> userCreatedDiskStoresOnLoner = listDiskStores(true);

      //salvage all servers in the group
      extractDataFromServers(serverInfoGroup, listDiskStores(false));
      if (!useSingleDDL) {
        stopServer();
      }

      //clean up disk store files so that the next salvage won't have conflicts creating disk stores again
      ExtractorUtils.cleanDiskStores(defaultAndDataDictionaryDiskStores, true);
      //for some reason the default store references are "overridden" and not returned in this this so we explicitly remove them first.
      ExtractorUtils.cleanDiskStores(userCreatedDiskStoresOnLoner, false);
    }

    String outputDirectory = getOutputDirectory();
    long endTime = System.currentTimeMillis();

    double exportTime = (endTime - startTime)/1000d;
    System.out.println("Total extraction time : " + exportTime + "s");

    logInfo("Generating the extraction summary and recommendation...");
    ReportGenerator repGen = new ReportGenerator(rankedAndGroupedDdlStats, hostToStatsMap, reportableErrors);
    repGen.printReport(new File(outputDirectory, "Summary.txt").getAbsolutePath(), new File(outputDirectory, "Recommended.txt").getAbsolutePath());
    logInfo("Completed the generation of extraction summary and recommendation");
  }

  protected void createConnection(boolean enableOffHeap) throws SQLException {
    EmbedConnection embeddedConnection = null;
    loadDriver(DRIVER_STRING);
    Connection conn = getConnection("jdbc:gemfirexd:", createConnectionProperties(enableOffHeap));
    embeddedConnection = (EmbedConnection)conn;
    embeddedConnection.getTR().setupContextStack();
  }

  public void createTestConnection() throws SQLException {
    EmbedConnection embeddedConnection = null;
    loadDriver(DRIVER_STRING);
    Connection conn = getConnection("jdbc:gemfirexd:", new Properties());
    embeddedConnection = (EmbedConnection)conn;
    embeddedConnection.getTR().setupContextStack();
  }

  protected boolean isOffHeapUsed(List<String> ddlStatements) {
    boolean isOffHeapEnabled = false;

    Iterator<String> ddlIter = ddlStatements.iterator();

    while (ddlIter.hasNext()) {
      String ddlStatment = ddlIter.next();

      if (ddlStatment.toLowerCase().contains("offheap")) {
        logInfo("Found off heap tables in the schema");
        isOffHeapEnabled = true;
        break;
      }
    }
    return isOffHeapEnabled;
  }
  protected void extractDDLs(Map<String, ServerInfo> serverInfoMap) {
    Map<ServerInfo, FutureTask> ddlExportTasks = new HashMap<ServerInfo, FutureTask>();
    ExecutorService executor = Executors.newFixedThreadPool(10);
    Iterator<Entry<String, ServerInfo>> serverInfoIterator = serverInfoMap.entrySet().iterator();

    while (serverInfoIterator.hasNext()) {
      Entry<String, ServerInfo> entry = serverInfoIterator.next();
      final ServerInfo serverInfo = entry.getValue();
      FutureTask<List<GFXDSnapshotExportStat>> futureTask = new FutureTask<List<GFXDSnapshotExportStat>>(new Callable<List<GFXDSnapshotExportStat>>() {
        public List<GFXDSnapshotExportStat> call() throws Exception {
          return extractDDL(serverInfo);
        }
      });
      ddlExportTasks.put(serverInfo, futureTask);
      executor.execute(futureTask);
    }
    serverInfoIterator = serverInfoMap.entrySet().iterator();

    while (serverInfoIterator.hasNext()) {
      Entry<String, ServerInfo> entry = serverInfoIterator.next();
      final ServerInfo serverInfo = entry.getValue();
      try {
        FutureTask<List<GFXDSnapshotExportStat>> task = ddlExportTasks.get(serverInfo);
        List<GFXDSnapshotExportStat> ddlStats = task.get();
        if (ddlStats == null || ddlStats.isEmpty()) {
          logSevere("Unable to extract the schema for server : " + serverInfo.getServerName() + 
              ". Cannot proceed with the data extract for server", reportableErrors);
          continue;
        }
        hostToDdlMap.put(serverInfo, ddlStats);
      }
      catch (ExecutionException e) {
        logSevere("ExecutionException: Unable to extract the schema for server : " + serverInfo.getServerName() + 
            ". Cannot proceed with the data extract for server", reportableErrors);
      }
      catch (InterruptedException e) {
        logSevere("Interrupted: Unable to extract the schema for server : " + serverInfo.getServerName() + 
            ". Cannot proceed with the data extract for server", reportableErrors);
        Thread.currentThread().interrupt();
      }
    }

    executor.shutdown();
  }


  protected int getNumberOfThreads (List<ServerInfo> serverInfoList, List<DiskStoreImpl> diskStores) {
    /****
     * As we extract one diskstore at a time for a given server. 
     * Here the logic is we find the largest disk-store and use its size as a basis for worst case heap memory required 
     * for salvaging a server at a given time.
     * This helps in determining how may servers can we salvage in parallel for given heap memory.
     * This is a very conservative estimate. 
     */
    long maxDiskStoreSizeOnDisk = 0;
    int maxNumberOfServersInParallel = 1;
    final double mbDiv = Math.pow(1024, 2);

    for (ServerInfo serverInfo : serverInfoList) {
      long maxDiskStoreSizeForServer = ExtractorUtils.getMaxDiskStoreSizeForServer(serverInfo, diskStores);
      if (maxDiskStoreSizeOnDisk < maxDiskStoreSizeForServer) {
        maxDiskStoreSizeOnDisk = maxDiskStoreSizeForServer;
      }
    }

    logInfo("Maximum disk-store size on disk " + maxDiskStoreSizeOnDisk/mbDiv + " MB");
    MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    MemoryUsage heapMemUsage = memBean.getHeapMemoryUsage();
    long usedMemory = heapMemUsage.getUsed();
    long committedMemory = heapMemUsage.getCommitted();

    //For safety using the committedMemory for calculation, as it is the memory that is guaranteed to be available for the VM. 
    long availableMemory = (committedMemory - usedMemory);
    logInfo("Available memory : " + availableMemory/mbDiv + " MB");

    double maxMemoryPerServer = (2.2)*maxDiskStoreSizeOnDisk;

    //Setting the lower limit
    if (maxMemoryPerServer < 1) {
      maxMemoryPerServer = 1;
    }

    logInfo("Estimated memory needed per server : " + maxMemoryPerServer/mbDiv + " MB");

    if (availableMemory < maxMemoryPerServer) {
      logWarning("Not enough memory to extract the server, extractor could possibly run out of memory");
    }

    maxNumberOfServersInParallel =  (int) (availableMemory/maxMemoryPerServer);

    if (maxNumberOfServersInParallel < 1) {
      maxNumberOfServersInParallel = 1;
    }

    logInfo("Recommended number of threads to extract server(s) in parallel : " + maxNumberOfServersInParallel);
    return maxNumberOfServersInParallel;
  }


  protected List<GFXDSnapshotExportStat> extractDDL(ServerInfo serverInfo) {
    String serverName = serverInfo.getServerName();
    String serverDirectory = serverInfo.getServerDirectory();
    List<GFXDSnapshotExportStat> ddlStats = null;
    try {
      String serverOutputDirectory = getServerOutputDirectory(serverName);
      if (extractInServerDir) {
        serverOutputDirectory = FilenameUtils.concat(serverDirectory, getSalvageDirName());
      }
      logInfo("Extracting DDL for server : " + serverInfo.getServerName());
      ddlStats = exportOfflineDDL(serverDirectory + File.separator + "datadictionary", serverOutputDirectory, stringDelimiter);
      logInfo("Completed extraction of DDL's for server : " + serverInfo.getServerName());
    }
    catch (DiskAccessException e) {
      logSevere("Disk Access issues, possibly permissions related for " + serverDirectory + ".  Issue: " +  e.getMessage(), e, reportableErrors);
    }
    catch (Exception e) {
      logSevere("Error occured while extracting the DDL's from " + serverDirectory, e, reportableErrors);
    }
    return ddlStats;
  }

  //ddl and connection have already been established
  protected void extractDataFromServers(List<ServerInfo> serverInfoList, final List<DiskStoreImpl> diskStores) {
    final int numThreads = ExtractorUtils.getNumberOfThreads(serverInfoList, diskStores);
    int threadPoolSize = numThreads;

    if (userOverrideNumThreads) {
      if (numThreads < this.userNumThreads) {
        logWarning("User specified a high thread count. Extractor could possibly run out of memory.");
      }
      threadPoolSize = this.userNumThreads;
    }

    Map<ServerInfo, FutureTask> salvageServerTasks = new HashMap<ServerInfo, FutureTask>();
    ExecutorService executor = Executors.newFixedThreadPool(threadPoolSize);

    for (final ServerInfo serverInfo: serverInfoList) {

      FutureTask<List<GFXDSnapshotExportStat>> futureTask = new FutureTask<List<GFXDSnapshotExportStat>>(new Callable<List<GFXDSnapshotExportStat>>() {
        public List<GFXDSnapshotExportStat> call() {
          try {
            return extractDataFromServer(serverInfo, diskStores);
          } catch (Exception e) {
            logSevere("Exception occured while extracting the server " + serverInfo.getServerName(), e, reportableErrors);
          }

          return new ArrayList<GFXDSnapshotExportStat>();
        }
      });
      salvageServerTasks.put(serverInfo, futureTask);
      executor.execute(futureTask);
    }

    for (ServerInfo serverInfo: serverInfoList) {
      try {
        FutureTask<List<GFXDSnapshotExportStat>> task = salvageServerTasks.get(serverInfo);
        List<GFXDSnapshotExportStat> salvageServerStats = task.get();
        hostToStatsMap.put(serverInfo.getServerName(), salvageServerStats);
      }
      catch (ExecutionException e) {
        e.printStackTrace();
        logSevere("ExecutionException: Unable to extact the data for server : " + serverInfo.getServerName() + 
            ". Cannot proceed with the data extract for server", reportableErrors);
      }
      catch (InterruptedException e) {
        logSevere("Interrupted: Unable to extract the data for server : " + serverInfo.getServerName() + 
            ". Cannot proceed with the data extract for server", reportableErrors);
        Thread.currentThread().interrupt();
      }
    }
    executor.shutdown();
  }

  protected List<GFXDSnapshotExportStat> extractDataFromServer(ServerInfo serverInfo, List<DiskStoreImpl> diskStores) throws IOException, Exception {
    String serverName = serverInfo.getServerName();
    String serverDirectory = serverInfo.getServerDirectory();
    logInfo("Started data extraction for Server : " + serverName);
    List<String> diskStoreDirectories = serverInfo.getDiskStoreDirectories();

    String serverOutputDirectory = getServerOutputDirectory(serverName);

    if (extractInServerDir) {
      serverOutputDirectory = FilenameUtils.concat(serverDirectory, getSalvageDirName());
    }
    return extractDiskStores(serverName, diskStores, diskStoreDirectories, serverOutputDirectory);
  }


  //Debug method to print all tables in the system
  private void printTables() throws SQLException {
    Statement statement = null;
    try {
      statement = this.jdbcConn.createStatement();
      ResultSet result = statement.executeQuery(("select TABLESCHEMANAME, TABLENAME from SYS.SYSTABLES order by TABLESCHEMANAME"));
      while (result.next()) {
        String schema = result.getString(1);
        String table = result.getString(2);
        System.out.println("TABLE:" + schema + "." + table);
      }

      result = statement.executeQuery("select * from SYS.SYSCONGLOMERATES");
      while (result.next()) {
        String conglomerateName = result.getString("CONGLOMERATENAME");
        boolean isConstraint = result.getBoolean("ISCONSTRAINT");
        String conglomerateSchemaName = result.getString("CONGLOMSCHEMANAME");
        GemFireXDDataExtractorImpl.logInfo("CONGLOMERATE NAME : " + conglomerateName + "ISCONSTRAINT : "  + isConstraint + "CONGLOMSCHEMANAME" + conglomerateSchemaName);
      }

      result = statement.executeQuery(" select * from SYS.SYSCONSTRAINTS");
      while (result.next()) {
        String constraintName = result.getString("CONSTRAINTNAME");
        GemFireXDDataExtractorImpl.logInfo("CONSTRAINTNAME" + constraintName + "ISCONSTRAINT : ");
      }
    }
    finally {
      if (statement != null) {
        statement.close();
      }
    }
  }

  public void retrieveAllRowFormatters() throws SQLException, StandardException {
    GFXDSnapshotExporter.tableNameRowFormatterMap = retrieveAllRowFormatters(this.jdbcConn);
  }

  private Map<String, RowFormatter> retrieveAllRowFormatters(Connection jdbcConn) throws SQLException, StandardException {
    Map<String, RowFormatter> tableNameRowFormatterMap = new ConcurrentHashMap<String, RowFormatter>();
    Statement statement = jdbcConn.createStatement();
    ResultSet result = statement.executeQuery(("select TABLESCHEMANAME, TABLENAME from SYS.SYSTABLES order by TABLESCHEMANAME"));
    while (result.next()) {
      String schemaName = result.getString(1);
      String tableName = result.getString(2);
      String schemaTableName = schemaName + tableName;

      if (!schemaName.equals("SYS")) {
        try {
          DataDictionary dd = Misc.getMemStore().getDatabase()
              .getDataDictionary();
          TransactionController tc = Misc.getLanguageConnectionContext()
              .getTransactionCompile();
          SchemaDescriptor sd = dd.getSchemaDescriptor(schemaName, tc, true);

          TableDescriptor tableDescriptor = dd.getTableDescriptor(tableName, sd,
              tc);

          if (tableDescriptor == null) {
            sd = dd.getSchemaDescriptor("APP", tc, true);
            tableDescriptor = dd.getTableDescriptor(tableName, sd, tc);
          }

          GemFireContainer container = Misc.getMemStore().getContainer(
              ContainerKey.valueOf(ContainerHandle.TABLE_SEGMENT,
                  tableDescriptor.getHeapConglomerateId()));
          RowFormatter rowFormatter = container.getCurrentRowFormatter();
          if (rowFormatter != null) {
            tableNameRowFormatterMap.put(schemaTableName, rowFormatter);
          }
          else {
            System.out.println("NULL ROW FORMATTER FOR:" + schemaTableName);
          }
        } catch (NullPointerException npe) {
          logInfo("Could not get meta data for " + schemaName + "." + tableName);
        }

      }
    }
    return tableNameRowFormatterMap;
  }


  protected static String getUserInput() {
    logInfo("Possibly insufficient disk space to carry out data extraction");
    String userInput = null;
    do {
      System.out.println("Do you wish to continue [y\n] ?");
      Scanner in = new Scanner(System.in);
      userInput = in.next();
    } while(!("y".equalsIgnoreCase(userInput) || "n".equalsIgnoreCase(userInput)));

    return userInput;
  }

  public void processArgs(String []args) throws Exception {
    if (args == null || args.length == 0) {
      throw new Exception("Please provide the 'property-file' , try --help for arguments and options");
    }
    for (String arg : args) {
      if (arg.equals(HELP_OPT)) {
        extractorProperties.setProperty(HELP_OPT, Boolean.toString(Boolean.TRUE));
      } else {
        String [] tokens = arg.split("=");
        if (tokens.length < 2) {
          throw new Exception("Invalid argument : " +  arg);
        }
        String key = tokens[0].trim();
        String value = tokens[1].trim();

        if (extractorProperties.containsKey(key)) {
          extractorProperties.setProperty(key, value);
        } else {
          throw new Exception("Invalid option : " + key);
        }
      }
    }
  }


  //Helper method for tests
  public List<GFXDSnapshotExportStat> extractDiskStores(String serverName, List<String> diskStoreDirectories, String serverOutputDirectory) throws Exception {
    //Based on the data dictionary generated , export the data from the oplogs.
    List<DiskStoreImpl> diskStores = listDiskStores(false);
    return extractDiskStores(serverName, diskStores, diskStoreDirectories, serverOutputDirectory);
  }


  protected List<GFXDSnapshotExportStat> extractDiskStores(String serverName, List<DiskStoreImpl> diskStores, List<String> diskStoreDirectories, String serverOutputDirectory) throws Exception {
    logInfo("Server : " + serverName + " Started extraction of  disk stores...");
    List<GFXDSnapshotExportStat> listOfStatsObjects = new ArrayList<GFXDSnapshotExportStat>();

    for (String diskStoreDirectory: diskStoreDirectories) {
      diskStoreDirectory = diskStoreDirectory.trim();
      for (DiskStoreImpl diskStore : diskStores) {
        try {

          List<GFXDSnapshotExportStat> listOfStats = extractDiskStore(serverName, diskStore, diskStoreDirectory, serverOutputDirectory);
          listOfStatsObjects.addAll(listOfStats);
        }
        catch (IllegalStateException e) {
          //most likely due to not having the file associated to a disk store in a different directory
          //logger.error("disk store:" + diskStore.getName() + " was not recovered in directory:" + diskStoreDirectory + " due to :" + e.getMessage());
          //System.out.println("disk store:" + diskStore.getName() + " was not recovered in directory:" + diskStoreDirectory + " due to :" + e.getMessage());
          logInfo("Disk-store:" + diskStore.getName() + " was not recovered from directory : " + diskStoreDirectory, e);
        }
        catch (DiskAccessException e) {
          logSevere("Could not access files for " + diskStore.getName() + " from directory " + diskStoreDirectory + " due to: " + e.getMessage(), reportableErrors);
        }
      }
    }
    logInfo("Server : " + serverName + " Completed extraction of disk stores");

    return listOfStatsObjects;
  }


  private List<GFXDSnapshotExportStat> extractDiskStore(String serverName, DiskStoreImpl diskStore, String diskStoreDirectory, String serverOutputDirectory) throws Exception {
    String diskStoreName = diskStore.getName();
    //System.out.println("Attempting export of diskstore:" + diskStoreName + " from directory: " + diskStoreDirectory);
    logInfo("Server : " + serverName +  " Attempting extraction of diskstore:" + diskStoreName + " from directory: " + diskStoreDirectory);
    if (!GFXDDiskStoreImpl.diskStoreExists(diskStore.getName(), new File(diskStoreDirectory))) {
      throw new IllegalStateException ("could not locate .if file for :" + diskStore.getName());
    }

    List<GFXDSnapshotExportStat> listOfStats = exportDataOpLog(diskStoreName, diskStoreDirectory, serverOutputDirectory, false, stringDelimiter);
    //cleanLockFiles(diskStore);
    boolean possibleCorrupt = false;
    List<GFXDSnapshotExportStat> listOfStatsUsingKRF = null;
    try {
      listOfStatsUsingKRF = exportDataOpLog(diskStoreName, diskStoreDirectory, serverOutputDirectory, true, stringDelimiter);
    }
    catch (DiskAccessException e) {
      possibleCorrupt = true;
    }

    detectCorruption(listOfStats, listOfStatsUsingKRF, possibleCorrupt);

    for (GFXDSnapshotExportStat stat : listOfStats) {
      stat.setServerName(serverName);
    }
    logInfo("Server : "+ serverName + "Completed extraction of diskstore:" + diskStoreName + " from directory: " + diskStoreDirectory);
    return listOfStats;
  }

  /****
   * Compares the stats from normal recovery and recovery using krf, and detects possible corruption
   * @param listOfStats
   * @param listOfStatsUsingKRF
   */
  private void detectCorruption(List<GFXDSnapshotExportStat> listOfStats, List<GFXDSnapshotExportStat> listOfStatsUsingKRF, boolean possibleCorrupt) {
    for (GFXDSnapshotExportStat stat : listOfStats) {
      if (possibleCorrupt) {
        stat.setCorrupt(true);
      }
      else {
        Iterator<GFXDSnapshotExportStat> iter = listOfStatsUsingKRF.iterator();
        while (iter.hasNext()) {
          GFXDSnapshotExportStat krfStat = (GFXDSnapshotExportStat) iter.next();
          if (stat.isSameTableStat(krfStat)) {
            if (stat.getNumValuesDecoded() != krfStat.getNumValuesDecoded()) {
              stat.setCorrupt(true);
              iter.remove();
              break;
            }
          }
        }
      }
    }
  }


  static List<GFXDSnapshotExportStat> exportDataOpLog(String diskStoreName, String inputDirectory, String outputDirectory, boolean forceKRFRecovery, String stringDelimiter) throws Exception {
    return GFXDDiskStoreImpl.exportOfflineSnapshotXD(diskStoreName, new File[] { new File(inputDirectory) }, new File(outputDirectory), forceKRFRecovery, stringDelimiter);
  }

  public static List<GFXDSnapshotExportStat> exportDataOpLog(String diskStoreName, String inputDirectory, String outputDirectory, boolean forceKRFRecovery) throws Exception {
    return GFXDDiskStoreImpl.exportOfflineSnapshotXD(diskStoreName, new File[] { new File(inputDirectory) }, new File(outputDirectory), forceKRFRecovery, DEFAULT_STRING_DELIMITER);
  }

  static List<GFXDSnapshotExportStat> exportOfflineDDL(String ddDirectory, String outputDirectory, String stringDelimiter) throws Exception {
    return GFXDDiskStoreImpl.exportOfflineSnapshotXD(GfxdConstants.GFXD_DD_DISKSTORE_NAME, new File[] { new File(ddDirectory)}, new File(outputDirectory), false, stringDelimiter);
  }

  public static List<GFXDSnapshotExportStat> exportOfflineDDL(String ddDirectory, String outputDirectory) throws Exception {
    return GFXDDiskStoreImpl.exportOfflineSnapshotXD(GfxdConstants.GFXD_DD_DISKSTORE_NAME, new File[] { new File(ddDirectory)}, new File(outputDirectory), false, DEFAULT_STRING_DELIMITER);
  }


  private static List<DiskStoreImpl> listDiskStores(boolean includeDataDictionary) {
    List<DiskStoreImpl> diskStoresList = new ArrayList<DiskStoreImpl>();
    GemFireCacheImpl cache = GemFireCacheImpl.getInstance();

    Collection<DiskStoreImpl> diskStores = cache.listDiskStoresIncludingRegionOwned();
    for (DiskStoreImpl diskStore : diskStores) {
      if (!diskStore.getName().equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME) || includeDataDictionary) {
        diskStoresList.add(diskStore);
      }
    }
    return diskStoresList;
  }


  public String getOutputDirectory() throws IOException {
    if (outputDirectory == null) {
      if (outputDirectoryPath == null || outputDirectoryPath.isEmpty()) {
        outputDirectoryPath = FilenameUtils.concat(".", getSalvageDirName());
      } else {
        outputDirectoryPath = FilenameUtils.concat(outputDirectoryPath, getSalvageDirName());
      }
      outputDirectory = new File(outputDirectoryPath);
      if (!outputDirectory.exists()) {
        if (!outputDirectory.mkdir()) {
          throw new IOException("Could not create output directory:" + outputDirectory.getAbsolutePath());
        }
      }
    }
    return outputDirectory.getCanonicalPath();
  }

  public String getSalvageDirName() {
    return extractedFilesDirPrefix;
  }

  public boolean useOverrodeNumThreads() {
    return this.userOverrideNumThreads;
  }

  public int getUserNumThreads() {
    return this.userNumThreads;
  }

  private String getServerOutputDirectory(String serverName) throws IOException  {
    String outputDirectory = getOutputDirectory();

    File file = new File(outputDirectory, serverName);
    if (!file.exists()) {
      if (!file.mkdirs()) {
        throw new IOException ("Server : " + serverName +" could not create output directories :" + file.getCanonicalPath());
      }
    }
    return file.getCanonicalPath();
  }

  /*** Connection and Driver Methods ***/
  private void stopServer() throws SQLException, Exception {
    try {
      if (this.jdbcConn != null) {
        this.jdbcConn.close();
      }

      DriverManager.deregisterDriver(driver);
      final FabricService fs = FabricServiceManager.getFabricServerInstance();

      if (fs != null) {
        fs.stop(new Properties());
      }

      final GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
      DistributedSystem ds = null;

      if (cache != null) {
        ds = cache.getDistributedSystem();
        if (ds != null) {
          ds.disconnect();
        }
      }
    } catch (Exception e) {
      logSevere("Unable to stop the server ", e , reportableErrors);
    }
    GFXDDiskStoreImpl.cleanUpOffline();
  }

  public void loadDriver(String driver) {
    try {
      if (this.driver == null) {
        this.driver = (Driver)Class.forName(driver).newInstance();
      }
    } catch (ClassNotFoundException cnfe) {
      cnfe.printStackTrace();
      //logger.error("Unable to load the JDBC driver ", cnfe);
      logSevere("Unable to load the JDBC driver ", cnfe, reportableErrors);

    } catch (InstantiationException ie) {
      ie.printStackTrace();
      //logger.error("Unable to instantiate the JDBC driver", ie);
      logSevere("Unable to instantiate the JDBC driver", ie, reportableErrors);
    } catch (IllegalAccessException iae) {
      iae.printStackTrace();
      logSevere("Not allowed to access the JDBC driver ", iae, reportableErrors);
    }
  }

  private boolean setPropertyIfAbsent(Properties props, String key, String value) {
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

  public Properties createConnectionProperties(boolean enableOffHeap) {
    String outputDir = ".";
    try {
      outputDir = getOutputDirectory();
    }
    catch (IOException e) {
    }

    String gemfirelog = FilenameUtils.concat(outputDir, "system");
    Properties props = new Properties();
    if (setPropertyIfAbsent(props, DistributionConfig.LOG_FILE_NAME, gemfirelog
        + ".log")) {
      // if no log-file property then also set the system property for
      // gemfirexd log-file that will also get used for JDBC clients
      setPropertyIfAbsent(null, GfxdConstants.GFXD_LOG_FILE, gemfirelog
          + ".log");
    }

    setPropertyIfAbsent(null, GfxdConstants.GFXD_CLIENT_LOG_FILE, gemfirelog
        + "-client.log");
    setPropertyIfAbsent(props, "mcast-port", "0");
    // set default partitioned policy if not set
    setPropertyIfAbsent(props, Attribute.TABLE_DEFAULT_PARTITIONED, "true");

    //Set the loner VM to use the off heap memory, to ensure creation of off heap table during DDL replay.
    if (enableOffHeap) {
      setPropertyIfAbsent(props, DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "1m");
    }

    if (!StringUtils.isEmpty(this.userName)) {
      setPropertyIfAbsent(props, Attribute.USERNAME_ATTR, userName);
    }
    return props;
  }

  public synchronized Connection getConnection(String protocol,
      Properties props) throws SQLException {
    // We want to control transactions manually. Autocommit is on by
    // default in JDBC.
    // Read the flag for deleting persistent files only during boot up
    if (jdbcConn == null || jdbcConn.isClosed()) {
      final Connection conn = DriverManager.getConnection(protocol, props);
      jdbcConn = conn;
    }
    return jdbcConn;
  }

  public Map<String, List<GFXDSnapshotExportStat>> getHostToStatsMap() {
    return this.hostToStatsMap;
  }


  public static void logSevere(String msg) {
    logSevere(msg, false);
  }

  public static void logSevere(String msg, boolean reportError) {
    logSevere(msg, getReportableErrors());
  }

  public static void logSevere(String msg, List<String> reportableErrors) {
    System.out.println(msg);
    logger.log(Level.SEVERE, msg);
    if (reportableErrors != null) {
      reportableErrors.add(msg);
    }
    if (logFileHandler != null) {
      logFileHandler.flush();
    }
  }

  public static void logSevere(String msg, Throwable e) {
    logSevere(msg, e, null);
  }

  public static void logSevere(String msg, Throwable e, List<String> reportableErrors) {
    System.out.println(msg);
    logger.log(Level.SEVERE, msg, e);
    if (reportableErrors != null) {
      reportableErrors.add(msg + "::" + e.getMessage());
    }
    if (logFileHandler != null) {
      logFileHandler.flush();
    }
  }

  public static void logInfo(String msg) {
    System.out.println(msg);
    logger.log(Level.INFO, msg);
  }

  public static void logInfo(String msg, Throwable e) {
    System.out.println(msg);
    logger.log(Level.INFO, msg, e);
  }

  public static void logConfig(String msg) {
    System.out.println(msg);
    logger.log(Level.CONFIG, msg);
  }

  public static void logFine(String msg) {
    System.out.println(msg);
    logger.log(Level.FINE, msg);
  }

  public static void logWarning(String msg) {
    System.out.println("WARNING : " + msg);
    logger.warning(msg);
  }



  public static void main(String[] args) throws Exception {
    doMain(args);
  }

  //created to simplify tests
  public static GemFireXDDataExtractorImpl doMain(String[] args) throws Exception {
    try {
      if (args.length < 1) {
        throw new Exception("Please specify the location of extraction properties file");
      }

      GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
      extractor.processArgs(args);
      extractor.consumeProperties();
      extractor.configureLogger();
      extractor.extract();
      return extractor;
    }
    finally {
      if (logFileHandler != null) {
        logFileHandler.flush();
        logFileHandler.close();
      }
    }
  }

  public String getPropFilePath() {
    return propFilePath;
  }

  public void setPropFilePath(String propFilePath) {
    this.propFilePath = propFilePath;
  }

  public boolean isShowHelp() {
    return showHelp;
  }

  public void setShowHelp(boolean showHelp) {
    this.showHelp = showHelp;
  }

  public String getSingleDdlFilePath() {
    return singleDdlFilePath;
  }

  public void setSingleDdlFilePath(String singleDdlFilePath) {
    this.singleDdlFilePath = singleDdlFilePath;
  }

  public String getStringDelimiter() {
    return stringDelimiter;
  }

  public boolean useSingleDDl() {
    return this.useSingleDDL;
  }


  public static Logger getLogger() {
    return logger;
  }

  protected static FileHandler getLogFileHandler() {
    return logFileHandler;
  }
  protected Connection getJdbcConn() {
    return jdbcConn;
  }

  protected Driver getDriver() {
    return driver;
  }

  protected Map<ServerInfo, List<GFXDSnapshotExportStat>> getHostToDdlMap() {
    return hostToDdlMap;
  }

  public boolean isUseSingleDDL() {
    return useSingleDDL;
  }

  public boolean isSalvageInServerDir() {
    return extractInServerDir;
  }

  public String getLogFilePath() {
    return logFilePath;
  }

  public String getLogLevelString() {
    return logLevelString;
  }

  public Properties getToolProperties() {
    return extractorProperties;
  }

  public static String getDefaultStringDelimiter() {
    return DEFAULT_STRING_DELIMITER;
  }

  public String getSalvageDirPrefix() {
    return extractedFilesDirPrefix;
  }

  public String getOutputDirectoryPath() {
    return outputDirectoryPath;
  }


  public static List<String> getReportableErrors() {
    return reportableErrors;
  }

  public List<List<GFXDSnapshotExportStat>> getRankedAndGroupedDDLStats() {
    return this.rankedAndGroupedDdlStats;
  }
}



