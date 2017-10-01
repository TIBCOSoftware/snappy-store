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
package com.pivotal.gemfirexd.internal.tools.dataextractor;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FilenameFilter;
import java.io.IOException;
import java.io.StringReader;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.cache.CacheException;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.tools.dataextractor.extractor.GemFireXDDataExtractorImpl;
import com.pivotal.gemfirexd.internal.tools.dataextractor.snapshot.GFXDSnapshotExportStat;
import com.pivotal.gemfirexd.internal.tools.dataextractor.utils.ExtractorUtils;
import io.snappydata.test.dunit.SerializableRunnable;
import org.apache.commons.io.FileUtils;


public class GemFireXDDataExtractorDUnit  extends DistributedSQLTestBase {

  private String testDiskStoreName = "TESTDISKSTORE";
  private String secondDiskStoreName = "SECONDDISKSTORE";
  private String copyDirectory = "copyWorkingDirectory";
  
  public GemFireXDDataExtractorDUnit(String name) {
    super(name);
  }
  
  //Due to the salvager starting a loner instance in the current directory,
  //we need to disconnect the test instance that is currently using the test directory
  //before actually starting the salvage process.
  private void disconnectTestInstanceAndCleanUp() throws SQLException {
    ExtractorUtils.cleanWorkingDirectory();
//    DistributedSystem ds = GemFireCacheImpl.getInstance().getDistributedSystem();
//    //GemFireCacheImpl.getInstance().close();
//    FabricServiceManager
//    .currentFabricServiceInstance().stop(new Properties());
//    ds.disconnect();
    TestUtil.shutDown();
  }
  @Override
  public void setUp() throws Exception {
    super.setUp();
    this.configureDefaultOffHeap(true);
    ExtractorUtils.cleanWorkingDirectory();
  }
  
  
  @Override
  public void tearDown2() throws Exception {
    super.tearDown2();
    stopAllVMs();
    shutDownAll();
  }

  public void testHappyPathDataSalvager() throws Exception {
    String tableName = "REPLICATE_TABLE";
    String server1Name = "server1";
    String server2Name = "server2";
    int numDDL = 3;
    startVMs(1, 2);
    clientCreateDiskStore(1, testDiskStoreName);
   
    //Create a json table with and insert json data into it
    
    String[] jsonStrings = new String[6];
    jsonStrings[0] = "{\"f1\":1,\"f2\":true}";
    jsonStrings[1] = "{\"f3\":1,\"f4\":true}";
    jsonStrings[2] = "{\"f5\":1,\"f6\":true}";
    jsonStrings[3] = null;
    jsonStrings[4] = "{\"f6\":1,\"f7\":true}";
    jsonStrings[5] = "{\"f7\":1,\"f8\":true}";
    
    String createTable = "CREATE table t1(col1 int, col2 json) REPLICATE persistent 'TESTDISKSTORE'";
    clientSQLExecute(1, createTable);
    clientSQLExecute(1, "insert into t1 values (1, '" + jsonStrings[0] + "')");
    clientSQLExecute(1, "insert into t1 values (2, '{\"JSON\":\"JSON\"}')");
    
    clientCreatePersistentReplicateTable(1, tableName, testDiskStoreName);
    //create table
    //insert/update/delete data
    insertData(tableName, 0, 1000);
    updateData(tableName, 300, 600);
    deleteData(tableName, 600, 900);
   
    String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
    String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
    
    this.serverExecute(1, this.getCopyDataDictionary(server1Directory));
    this.serverExecute(2,  this.getCopyDataDictionary(server2Directory));
    
    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, testDiskStoreName));
    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, testDiskStoreName));

    String propertiesFileName = "test_salvage.properties";
    Properties properties = new Properties();
    String server1CopyDir = server1Directory + File.separator + copyDirectory;
    String server2CopyDir = server2Directory + File.separator + copyDirectory;
    
    properties.setProperty(server1Name, server1CopyDir + "," + server1CopyDir + File.separator + testDiskStoreName);
    properties.setProperty(server2Name, server2CopyDir + "," + server2CopyDir + File.separator + testDiskStoreName);
    properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");

    String userName =  TestUtil.currentUserName == null? "APP":TestUtil.currentUserName;
    //pass in properties file to salvager
    String args[] = new String[] {"property-file=" + propertiesFileName, "--user-name=" + userName};
    disconnectTestInstanceAndCleanUp();
    
    GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);

    String outputDirectoryString = salvager.getOutputDirectory();
    File outputDirectory = new File(outputDirectoryString);
    assertTrue(outputDirectory.exists());
    
    File server1OutputDirectory = new File(outputDirectory, server1Name);
    assertTrue(serverExportedCorrectly(server1OutputDirectory, new String[] {tableName}, numDDL));
    
    File server2OutputDirectory = new File(outputDirectory, server2Name);
    assertTrue(serverExportedCorrectly(server2OutputDirectory, new String[] {tableName}, numDDL));

    File summaryFile = new File(outputDirectory, "Summary.txt");
    assertTrue(summaryFile.exists());
    
    File recommended = new File(outputDirectory, "Recommended.txt");
    assertTrue(recommended.exists());

    // FileUtils.copyDirectory(outputDirectory, new File("/kuwait1/users/bansods/test"));
    FileUtils.deleteQuietly(outputDirectory);
  }

  public void testMultiDiskStore() throws Exception {
    String tableName = "REPLICATE_TABLE";
    String secondTableName = "REPLICATE_2_TABLE";
    String server1Name = "server1";
    String server2Name = "server2";
    int numDDL = 4;
    startVMs(1, 2);
    clientCreateDiskStore(1, testDiskStoreName);
    clientCreateDiskStore(1, secondDiskStoreName);
   
    clientCreatePersistentReplicateTable(1, tableName, testDiskStoreName);
    clientCreatePersistentReplicateTable(1, secondTableName, secondDiskStoreName);
    //create table
    //insert/update/delete data
    insertData(tableName, 0, 1000);
    updateData(tableName, 300, 600);
    deleteData(tableName, 600, 900);
    
    insertData(secondTableName, 0, 1000);
    updateData(secondTableName, 300, 600);
    deleteData(secondTableName, 600, 900);

    String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
    String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
    
    this.serverExecute(1, this.getCopyDataDictionary(server1Directory));
    this.serverExecute(2,  this.getCopyDataDictionary(server2Directory));
    
    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, testDiskStoreName));
    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, testDiskStoreName));
    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, secondDiskStoreName));
    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, secondDiskStoreName));

    String propertiesFileName = "test_salvage.properties";
    Properties properties = new Properties();
    String server1CopyDir = server1Directory + File.separator + copyDirectory;
    String server2CopyDir = server2Directory + File.separator + copyDirectory;
    properties.setProperty(server1Name, server1CopyDir + "," + server1CopyDir + File.separator + testDiskStoreName + "," + server1CopyDir + File.separator + secondDiskStoreName);
    properties.setProperty(server2Name, server2CopyDir + "," + server2CopyDir + File.separator + testDiskStoreName + "," + server2CopyDir + File.separator + secondDiskStoreName);
    properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");
    
    String userName =  TestUtil.currentUserName == null? "APP":TestUtil.currentUserName;
    //pass in properties file to salvager
    String args[] = new String[] {"property-file=" + propertiesFileName, "--user-name=" + userName};
    disconnectTestInstanceAndCleanUp();
    
    GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);

    String outputDirectoryString = salvager.getOutputDirectory();
    File outputDirectory = new File(outputDirectoryString);
    assertTrue(outputDirectory.exists());
    
    File server1OutputDirectory = new File(outputDirectory, server1Name);
    assertTrue(serverExportedCorrectly(server1OutputDirectory, new String[] {tableName, secondTableName}, numDDL));
    
    File server2OutputDirectory = new File(outputDirectory, server2Name);
    assertTrue(serverExportedCorrectly(server2OutputDirectory, new String[] {tableName, secondTableName}, numDDL));

    File summaryFile = new File(outputDirectory, "Summary.txt");
    assertTrue(summaryFile.exists());
    
    File recommended = new File(outputDirectory, "Recommended.txt");
    assertTrue(recommended.exists());
    FileUtils.deleteQuietly(outputDirectory);
  }
  
//  public void DISABLED_BUG51473testHappyPathDataSalvagerAndLoader() throws Exception {
//    String tableName = "REPLICATE_TABLE";
//    String server1Name = "server1";
//    String server2Name = "server2";
//    int numDDL = 2;
//    this.startVMs(1, 1);
//    this.startNetworkServer(2, null, null);
//    clientCreateDiskStore(1, testDiskStoreName);
//   
//    clientCreatePersistentReplicateTable(1, tableName, testDiskStoreName);
//    //create table
//    //insert/update/delete data
//    insertData(tableName, 0, 1000);
//    updateData(tableName, 300, 600);
//    deleteData(tableName, 600, 900);
//    String server1Directory = getVM(-1).getSystem().getSystemDirectory() + "_" + getVM(-1).getPid();
//    String server2Directory = getVM(-2).getSystem().getSystemDirectory() + "_" + getVM(-2).getPid();
//    
//    this.serverExecute(1, this.getCopyDataDictionary(server1Directory));
//    this.serverExecute(2,  this.getCopyDataDictionary(server2Directory));
//    
//    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, testDiskStoreName));
//    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, testDiskStoreName));
//
//    String propertiesFileName = "test_salvage.properties";
//    Properties properties = new Properties();
//    String server1CopyDir = server1Directory + File.separator + copyDirectory;
//    String server2CopyDir = server2Directory + File.separator + copyDirectory;
//    
//    properties.setProperty(server1Name, server1CopyDir + "," + server1CopyDir + File.separator + testDiskStoreName);
//    properties.setProperty(server2Name, server2CopyDir + "," + server2CopyDir + File.separator + testDiskStoreName);
//    properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");
//
//    String userName =  TestUtil.currentUserName == null? "APP":TestUtil.currentUserName;
//    //pass in properties file to salvager
//    String args[] = new String[] {"property-file=" + propertiesFileName, "--user-name=" + userName};
//    disconnectTestInstanceAndCleanUp();
//    GemFireXDDataSalvager salvager = GemFireXDDataSalvager.doMain(args);
//
//    String outputDirectoryString = salvager.getOutputDirectory();
//    File outputDirectory = new File(outputDirectoryString);
//    assertTrue(outputDirectory.exists());
//    
//    File server1OutputDirectory = new File(outputDirectory, server1Name);
//    assertTrue(serverExportedCorrectly(server1OutputDirectory, new String[] {tableName}, numDDL));
//    
//    File server2OutputDirectory = new File(outputDirectory, server2Name);
//    assertTrue(serverExportedCorrectly(server2OutputDirectory, new String[] {tableName}, numDDL));
//
//    File summaryFile = new File(outputDirectory, "Summary.txt");
//    assertTrue(summaryFile.exists());
//    
//    File recommended = new File(outputDirectory, "Recommended.txt");
//    assertTrue(recommended.exists());  
//    
//    System.out.println("JASON LOCATOR " + this.getLocatorString());
//    String[] split = this.getLocatorString().split("\\[");
//    String port = split[1].substring(0, split[1].length() - 1);
//    
//    GemFireXDDataSalvageLoader.main(new String[]{"recommended=/Users/jhuynh/VMware/gemfires/salvagerepo2/SALVAGED_FILES_2014-03-05_10:38:04/Recommended.txt " + "host="+ split[0], "port=" + port});
//  }
  
  public void testCorruptCRFOnOneNode() throws Exception {
    String tableName = "REPLICATE_TABLE";
    String server1Name = "server1";
    String server2Name = "server2";
    int numDDL = 2;
    startVMs(1, 2);
    clientCreateDiskStore(1, testDiskStoreName);
   
    clientCreatePersistentReplicateTable(1, tableName, testDiskStoreName);
    //create table
    //insert/update/delete data
    insertData(tableName, 0, 100);
    updateData(tableName, 30, 60);
    deleteData(tableName, 60, 90);
   
    String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
    String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
    
    this.serverExecute(1, this.getCopyDataDictionary(server1Directory));
    this.serverExecute(2,  this.getCopyDataDictionary(server2Directory));
    
    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, testDiskStoreName));
    this.serverExecute(1, this.getCorruptOplogsRunnable(server1Directory,testDiskStoreName, new String[]{".crf"}, 400 /*bytes to preserve*/));
    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, testDiskStoreName));

    String propertiesFileName = "test_salvage.properties";
    Properties properties = new Properties();
    String server1CopyDir = server1Directory + File.separator + copyDirectory;
    String server2CopyDir = server2Directory + File.separator + copyDirectory;
    properties.setProperty(server1Name, server1CopyDir + "," + server1CopyDir + File.separator + testDiskStoreName);
    properties.setProperty(server2Name, server2CopyDir + "," + server2CopyDir + File.separator + testDiskStoreName);
    properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");

    String userName =  TestUtil.currentUserName == null? "APP":TestUtil.currentUserName;
    //pass in properties file to salvager
    String args[] = new String[] {"property-file=" + propertiesFileName, "--user-name=" + userName};
    disconnectTestInstanceAndCleanUp();
    GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);

    String outputDirectoryString = salvager.getOutputDirectory();
    File outputDirectory = new File(outputDirectoryString);
    assertTrue(outputDirectory.exists());
    
    File server1OutputDirectory = new File(outputDirectory, server1Name);
    assertTrue(serverExportedCorrectly(server1OutputDirectory, new String[] {}, numDDL));
    
    File server2OutputDirectory = new File(outputDirectory, server2Name);
    assertTrue(serverExportedCorrectly(server2OutputDirectory, new String[] {tableName}, numDDL));

    File summaryFile = new File(outputDirectory, "Summary.txt");
    assertTrue(summaryFile.exists());
    
    File recommended = new File(outputDirectory, "Recommended.txt");
    assertTrue(recommended.exists());
    FileUtils.deleteQuietly(outputDirectory);
  }
  
  public void testCorruptIfOnOneNode() throws Exception {
    String tableName = "REPLICATE_TABLE";
    String server1Name = "server1";
    String server2Name = "server2";
    int numDDL = 2;
    startVMs(1, 2);
    clientCreateDiskStore(1, testDiskStoreName);
   
    clientCreatePersistentReplicateTable(1, tableName, testDiskStoreName);
    //create table
    //insert/update/delete data
    insertData(tableName, 0, 100);
    updateData(tableName, 30, 60);
    deleteData(tableName, 60, 90);
   
    String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
    String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
    
    this.serverExecute(1, this.getCopyDataDictionary(server1Directory));
    this.serverExecute(2,  this.getCopyDataDictionary(server2Directory));
    
    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, testDiskStoreName));
    this.serverExecute(1, this.getCorruptIfRunnable(server1Directory,testDiskStoreName));
    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, testDiskStoreName));

    String propertiesFileName = "test_salvage.properties";
    Properties properties = new Properties();
    String server1CopyDir = server1Directory + File.separator + copyDirectory;
    String server2CopyDir = server2Directory + File.separator + copyDirectory;
    properties.setProperty(server1Name, server1CopyDir + "," + server1CopyDir + File.separator + testDiskStoreName);
    properties.setProperty(server2Name, server2CopyDir + "," + server2CopyDir + File.separator + testDiskStoreName);
    properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");

    String userName =  TestUtil.currentUserName == null? "APP":TestUtil.currentUserName;
    //pass in properties file to salvager
    String args[] = new String[] {"property-file=" + propertiesFileName, "--user-name=" + userName};
    disconnectTestInstanceAndCleanUp();
    GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);

    String outputDirectoryString = salvager.getOutputDirectory();
    File outputDirectory = new File(outputDirectoryString);
    assertTrue(outputDirectory.exists());
    
    File server1OutputDirectory = new File(outputDirectory, server1Name);
    assertTrue(serverExportedCorrectly(server1OutputDirectory, new String[] {}, numDDL));
    
    File server2OutputDirectory = new File(outputDirectory, server2Name);
    assertTrue(serverExportedCorrectly(server2OutputDirectory, new String[] {tableName}, numDDL));

    File summaryFile = new File(outputDirectory, "Summary.txt");
    assertTrue(summaryFile.exists());
    
    File recommended = new File(outputDirectory, "Recommended.txt");
    assertTrue(recommended.exists());

    FileUtils.deleteQuietly(outputDirectory);
  }
  
  public void testMissingCRFOnOneNode() throws Exception {
    String tableName = "REPLICATE_TABLE";
    String server1Name = "server1";
    String server2Name = "server2";
    int numDDL = 2;
    startVMs(1, 2);

    clientCreateDiskStore(1, testDiskStoreName);
   
    clientCreatePersistentReplicateTable(1, tableName, testDiskStoreName);
    //create table
    //insert/update/delete data
    insertData(tableName, 0, 100);
    updateData(tableName, 30, 60);
    deleteData(tableName, 60, 90);
    String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
    String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
    
    this.serverExecute(1, this.getCopyDataDictionary(server1Directory));
    this.serverExecute(2,  this.getCopyDataDictionary(server2Directory));
    
    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, testDiskStoreName));
    this.serverExecute(1, this.getDeleteOplogsRunnable(server1Directory,testDiskStoreName, new String[]{".crf"}));
    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, testDiskStoreName));

    String propertiesFileName = "test_salvage.properties";
    Properties properties = new Properties();
    String server1CopyDir = server1Directory + File.separator + copyDirectory;
    String server2CopyDir = server2Directory + File.separator + copyDirectory;
    properties.setProperty(server1Name, server1CopyDir + "," + server1CopyDir + File.separator + testDiskStoreName);
    properties.setProperty(server2Name, server2CopyDir + "," + server2CopyDir + File.separator + testDiskStoreName);
    properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");

    String userName =  TestUtil.currentUserName == null? "APP":TestUtil.currentUserName;
    //pass in properties file to salvager
    String args[] = new String[] {"property-file=" + propertiesFileName, "--user-name=" + userName};
    disconnectTestInstanceAndCleanUp();
    GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);

    String outputDirectoryString = salvager.getOutputDirectory();
    File outputDirectory = new File(outputDirectoryString);
    assertTrue(outputDirectory.exists());
    
    File server1OutputDirectory = new File(outputDirectory, server1Name);
    assertTrue(serverExportedCorrectly(server1OutputDirectory, new String[] {}, numDDL));
    
    File server2OutputDirectory = new File(outputDirectory, server2Name);
    assertTrue(serverExportedCorrectly(server2OutputDirectory, new String[] {tableName}, numDDL));

    File summaryFile = new File(outputDirectory, "Summary.txt");
    assertTrue(summaryFile.exists());
    
    File recommended = new File(outputDirectory, "Recommended.txt");
    assertTrue(recommended.exists());

    FileUtils.deleteQuietly(outputDirectory);
  }
  
  public void testCorruptDataDictionary() throws Exception {
    String tableName = "REPLICATE_TABLE";
    String server1Name = "server1";
    String server2Name = "server2";
    int numDDL = 2;
    startVMs(1, 2);
    clientCreateDiskStore(1, testDiskStoreName);
   
    clientCreatePersistentReplicateTable(1, tableName, testDiskStoreName);
    //create table
    //insert/update/delete data
    insertData(tableName, 0, 100);
    updateData(tableName, 30, 60);
    deleteData(tableName, 60, 90);
    String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
    String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
    
    //Don't copy the data dictionary for this test
    this.serverExecute(1, this.getCopyDataDictionary(server1Directory));
    this.serverExecute(1, this.getCorruptOplogsRunnable(server1Directory, "datadictionary", new String[]{".crf"}, 200));
    this.serverExecute(2,  this.getCopyDataDictionary(server2Directory));
    
    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, testDiskStoreName));
    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, testDiskStoreName));

    String propertiesFileName = "test_salvage.properties";
    Properties properties = new Properties();
    String server1CopyDir = server1Directory + File.separator + copyDirectory;
    String server2CopyDir = server2Directory + File.separator + copyDirectory;
    properties.setProperty(server1Name, server1CopyDir + "," + server1CopyDir + File.separator + testDiskStoreName);
    properties.setProperty(server2Name, server2CopyDir + "," + server2CopyDir + File.separator + testDiskStoreName);
    properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");

    String userName =  TestUtil.currentUserName == null? "APP":TestUtil.currentUserName;
    //pass in properties file to salvager
    String args[] = new String[] {"property-file=" + propertiesFileName, "--user-name=" + userName};
    disconnectTestInstanceAndCleanUp();
    GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);

    String outputDirectoryString = salvager.getOutputDirectory();
    File outputDirectory = new File(outputDirectoryString);
    assertTrue(outputDirectory.exists());
    
    //File server1OutputDirectory = new File(outputDirectory, server1Name);
    this.serverExecute(1, getCheckDDLSizeRunnable(server1Directory, 0));
    
    File server2OutputDirectory = new File(outputDirectory, server2Name);
    assertTrue(serverExportedCorrectly(server2OutputDirectory, new String[] {tableName}, numDDL));

    File summaryFile = new File(outputDirectory, "Summary.txt");
    assertTrue(summaryFile.exists());
    
    File recommended = new File(outputDirectory, "Recommended.txt");
    assertTrue(recommended.exists());
    FileUtils.deleteQuietly(outputDirectory);
  }
  
  
  public void testPersistentView() throws Exception {
    final String testName = "testPersistentView";
    final String diskStoreName = testName + testDiskStoreName;
    try {
      String tableName = "REPLICATE_TABLE";
      String server1Name = "server1";
      String server2Name = "server2";
      String server3Name = "server3";

      startVMs(1, 3);
      String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
      String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
      String server3Directory = getVM(-3).getWorkingDirectory().getAbsolutePath();

      clientCreateDiskStore(1, diskStoreName);
      clientCreatePersistentReplicateTable(1, tableName, diskStoreName);
      //create table
      //insert/update/delete data
      insertData(tableName, 0, 100);
      updateData(tableName, 30, 60);
      deleteData(tableName, 60, 90);
      
      stopVMNum(-1);
      
      clientDropTable(1, tableName);
      stopVMNum(-2);
     
      //clientDropDiskStore(1, diskStoreName);
      stopVMNum(-3);
      
      assertTrue(new File(server1Directory).exists());
      assertTrue(new File(server2Directory).exists());
      assertTrue(new File(server3Directory).exists());
      
      String propertiesFileName = "test_salvage.properties";
      Properties properties = new Properties();
      
      //Get the directories for the servers
      properties.setProperty(server1Name, server1Directory + "," + server1Directory + File.separator + diskStoreName);
      properties.setProperty(server2Name, server2Directory + "," + server2Directory + File.separator + diskStoreName);
      properties.setProperty(server3Name, server3Directory + "," + server3Directory + File.separator + diskStoreName);
      
      properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");
      String args[] = new String[] {"property-file=" + propertiesFileName};
      
      GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);
      Map<String, List<GFXDSnapshotExportStat>> hostToStatsMap = salvager.getHostToStatsMap();
      
      assertFalse(hostToStatsMap.isEmpty());
      assertTrue(hostToStatsMap.size() == 3);
      Set<String> servers = hostToStatsMap.keySet();
      
      for (String server : servers) {
        getLogWriter().info("Server name : " + server);
      }
      List<List<GFXDSnapshotExportStat>> rankedAndGroupedDDlStats = salvager.getRankedAndGroupedDDLStats();
      assertTrue(!rankedAndGroupedDDlStats.isEmpty());
      assertTrue(rankedAndGroupedDDlStats.size() == 2);
      
      List<GFXDSnapshotExportStat> statsGroup = rankedAndGroupedDDlStats.get(0);
      assertNotNull(statsGroup);
      GFXDSnapshotExportStat stat = statsGroup.get(0);
      assertNotNull(stat);
      assertTrue(stat.getServerName().equals(server3Name));
      
      stat = statsGroup.get(1);
      assertNotNull(stat);
      assertTrue(stat.getServerName().equals(server2Name));
      
      statsGroup = rankedAndGroupedDDlStats.get(1);
      assertNotNull(statsGroup);
      stat = statsGroup.get(0);
      assertTrue(stat.getServerName().equals(server1Name));
      FileUtils.deleteQuietly(new File(salvager.getOutputDirectory()));

      restartVMNums(-1,-2,-3);
    } finally {
      tearDown2();
      disconnectTestInstanceAndCleanUp();
    }
  }  
  
  public void testWithHDFSAndOffHeap() throws Exception {
    final String testName = "testWithHDFS";
    final String diskStoreName = testName + testDiskStoreName;
    final String hdfsStoreName = testName + "myHDFSStore";
    try {
      String tableName = "REPLICATE_TABLE";
      String server1Name = "server1";
      String server2Name = "server2";
      String server3Name = "server3";
      
      startVMs(1, 3);
      String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
      String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
      String server3Directory = getVM(-3).getWorkingDirectory().getAbsolutePath();
      
      clientCreateHDFSSTORE(1, hdfsStoreName);
      clientCreateBookingTableWithHDFS(1, hdfsStoreName);
      
      clientCreateDiskStore(1, diskStoreName);
      clientCreatePersistentReplicateTable(1, tableName, diskStoreName);
      
      insertRowsIntoBookingsTable(1, 100);
      deleteRowsFromBookingsTable(1, 20);
      
      //create table
      //insert/update/delete data
      insertData(tableName, 0, 100);
      updateData(tableName, 30, 60);
      deleteData(tableName, 60, 90);
     
      stopVMNum(-1);
      stopVMNum(-2);
      
      dropBookingTable(1);
      dropHDFSStore(1, hdfsStoreName);
      stopVMNum(-3);
      
      assertTrue(new File(server1Directory).exists());
      assertTrue(new File(server2Directory).exists());
      assertTrue(new File(server3Directory).exists());
      
      String propertiesFileName = "test_salvage.properties";
      Properties properties = new Properties();
      
      //Get the directories for the servers
      properties.setProperty(server1Name, server1Directory + "," + server1Directory + File.separator + diskStoreName);
      properties.setProperty(server2Name, server2Directory + "," + server2Directory + File.separator + diskStoreName);
      properties.setProperty(server3Name, server3Directory + "," + server3Directory + File.separator + diskStoreName);
      
      
      properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");
      String args[] = new String[] {"property-file=" + propertiesFileName};
      
      GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);
      Map<String, List<GFXDSnapshotExportStat>> hostToStatsMap = salvager.getHostToStatsMap();
      
      assertFalse(hostToStatsMap.isEmpty());
      assertTrue(hostToStatsMap.size() == 3);
      restartVMNums(-1, -2, -3);
      FileUtils.deleteQuietly(new File(salvager.getOutputDirectory()));

    } finally {
      tearDown2();
      disconnectTestInstanceAndCleanUp();
    }
  }  
  
  public void testMissingDataDictionary() throws Exception {
    String tableName = "REPLICATE_TABLE";
    String server1Name = "server1";
    String server2Name = "server2";
    int numDDL = 2;
    startVMs(1, 2);
    clientCreateDiskStore(1, testDiskStoreName);
   
    clientCreatePersistentReplicateTable(1, tableName, testDiskStoreName);
    //create table
    //insert/update/delete data
    insertData(tableName, 0, 100);
    updateData(tableName, 30, 60);
    deleteData(tableName, 60, 90);
    String server1Directory = getVM(-1).getWorkingDirectory().getAbsolutePath();
    String server2Directory = getVM(-2).getWorkingDirectory().getAbsolutePath();
    
    //Don't copy the data dictionary for this test
    //this.serverExecute(1, this.getCopyDataDictionary(server1Directory));
    this.serverExecute(2,  this.getCopyDataDictionary(server2Directory));
    
    this.serverExecute(1,  this.getCopyOplogsRunnable(server1Directory, testDiskStoreName));
    this.serverExecute(2,  this.getCopyOplogsRunnable(server2Directory, testDiskStoreName));

    String propertiesFileName = "test_salvage.properties";
    Properties properties = new Properties();
    String server1CopyDir = server1Directory + File.separator + copyDirectory;
    String server2CopyDir = server2Directory + File.separator + copyDirectory;
    
    properties.setProperty(server1Name, server1CopyDir + "," + server1CopyDir + File.separator + testDiskStoreName);
    properties.setProperty(server2Name, server2CopyDir + "," + server2CopyDir + File.separator + testDiskStoreName);
    properties.store(new FileOutputStream(new File(propertiesFileName)), "test properties");

    String userName =  TestUtil.currentUserName == null? "APP":TestUtil.currentUserName;
    //pass in properties file to salvager
    String args[] = new String[] {"property-file=" + propertiesFileName, "--user-name=" + userName};
    
    disconnectTestInstanceAndCleanUp();
    GemFireXDDataExtractorImpl salvager = GemFireXDDataExtractorImpl.doMain(args);

    String outputDirectoryString = salvager.getOutputDirectory();
    File outputDirectory = new File(outputDirectoryString);
    assertTrue(outputDirectory.exists());
    
    File server1OutputDirectory = new File(outputDirectory, server1Name);
    this.serverExecute(1, getCheckDDLSizeRunnable(server1Directory, 0));
    
    File server2OutputDirectory = new File(outputDirectory, server2Name);
    assertTrue(serverExportedCorrectly(server2OutputDirectory, new String[] {tableName}, numDDL));

    File summaryFile = new File(outputDirectory, "Summary.txt");
    assertTrue(summaryFile.exists());
    
    File recommended = new File(outputDirectory, "Recommended.txt");
    assertTrue(recommended.exists());
    FileUtils.deleteQuietly(outputDirectory);
  }
  
  
  //verify ddl is exported
  //verify csv is exported
  private boolean serverExportedCorrectly(File serverOutputDirectory, String[] tableNames, int numDDL) throws IOException {
    assertTrue(serverOutputDirectory.exists());
    //check that ddl file is exported
    String[] ddl = serverOutputDirectory.list(new FilenameFilter() {
      public boolean accept(File arg0, String arg1) {
        return arg1.endsWith(".sql");
      }
    });
    assertEquals(1, ddl.length);
    
    List<String> ddlStatements = new ArrayList<String>();
    for (int i = 0; i < ddl.length; i++) {
      ddlStatements.addAll(ExtractorUtils.readSqlStatements(new File(serverOutputDirectory, ddl[i]).getAbsolutePath()));
    }
    //See if the number of ddl are as expected.  
    //It is possible that we have additional ddl due to username/schema switch based on the randomness of the dunit infrastructure
    //We end up adding a set current schema 'username' for this and this will bump up our actual size by one
    assertTrue((numDDL == ddlStatements.size()) || (numDDL +1 == ddlStatements.size() /*see comment above*/));
    assertTrue(new File(serverOutputDirectory, ddl[0]).exists());
    
    String[] csvs = serverOutputDirectory.list(new FilenameFilter() {
      public boolean accept(File arg0, String arg1) {
        return arg1.endsWith(".csv");
      }
    });
    
    List<String> list = Arrays.asList(csvs);
   
    for (int i = 0; i < tableNames.length; i++) {
      if (!checkCsvsContainsTableName(csvs, tableNames[i])) {
        fail("directory did not include csv for table name:" + tableNames[i]);
      }
    }
    return true;
  }
  
  private boolean checkCsvsContainsTableName(String[] csvs, String tableName) throws IOException {
    for (int j = 0; j < csvs.length; j++) {
      if (csvs[j].contains(tableName) && !csvs[j].contains("ERROR")) {
//        GemFireXDDataSalvageJUnit.numLines(new File(csvs[j]));
        return true;
      }
    }
    return false;
  }
  
  private void insertData(String tableName, int startIndex, int endIndex) throws SQLException{
    Connection connection = TestUtil.getConnection();
    PreparedStatement ps = connection.prepareStatement("INSERT INTO " + tableName + "(bigIntegerField, blobField, charField," +
        "charForBitData, clobField, dateField, decimalField, doubleField, floatField, longVarcharForBitDataField, numericField," +
        "realField, smallIntField, timeField, timestampField, varcharField, varcharForBitData, xmlField) values( ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, xmlparse(document cast (? as clob) PRESERVE WHITESPACE))");
 
    for (int i = startIndex; i < endIndex; i++) {
      int lessThan10 = i % 10;

      ps.setLong(1, i); //BIG INT
      ps.setBlob(2,new ByteArrayInputStream(new byte[]{(byte)i,(byte)i,(byte)i,(byte)i}));
      ps.setString(3, ""+lessThan10);
      ps.setBytes(4, ("" + lessThan10).getBytes());
      ps.setClob(5, new StringReader("SOME CLOB " + i));
      ps.setDate(6, new Date(System.currentTimeMillis()));
      ps.setBigDecimal(7, new BigDecimal(lessThan10 + .8));
      ps.setDouble(8, i + .88);
      ps.setFloat(9, i + .9f);
      ps.setBytes(10, ("A" + lessThan10).getBytes());
      ps.setBigDecimal(11, new BigDecimal(i));
      ps.setFloat(12, lessThan10 * 1111);
      ps.setShort(13, (short)i);
      ps.setTime(14, new Time(System.currentTimeMillis()));
      ps.setTimestamp(15, new Timestamp(System.currentTimeMillis()));
      ps.setString(16, "HI" + lessThan10);
      ps.setBytes(17, ("" + lessThan10).getBytes());
      ps.setClob(18, new StringReader("<xml><sometag>SOME XML CLOB " + i + "</sometag></xml>"));
      ps.execute();
    }
  }
  
  private void deleteData(String tableName, int startIndex, int endIndex) throws SQLException {
    Connection connection = TestUtil.getConnection();
    PreparedStatement ps = connection.prepareStatement("delete from " + tableName +" where bigIntegerField=?");
    for (int i = startIndex; i < endIndex; i++) {
      ps.setLong(1, i);
      ps.execute();
    }
  }
  
  private void updateData(String tableName, int startIndex, int endIndex) throws SQLException {
    Connection connection = TestUtil.getConnection();
    PreparedStatement ps = connection.prepareStatement("UPDATE " + tableName + " set blobField=?, charField=?," +
        "charForBitData=?, clobField=?, dateField=?, decimalField=?, doubleField=?, floatField=?, longVarcharForBitDataField=?, numericField=?," +
        "realField=?, smallIntField=?, timeField=?, timestampField=?, varcharField=?, varcharForBitData=?, xmlField=xmlparse(document cast (? as clob) PRESERVE WHITESPACE) where bigIntegerField=?");
 
    for (int i = startIndex; i < endIndex; i++) {
      int lessThan10 = i % 10;

      ps.setBlob(1,new ByteArrayInputStream(new byte[]{(byte)i,(byte)i,(byte)i,(byte)i}));
      ps.setString(2, ""+lessThan10);
      ps.setBytes(3, ("" + lessThan10).getBytes());
      ps.setClob(4, new StringReader("UPDATE CLOB " + i));
      ps.setDate(5, new Date(System.currentTimeMillis()));
      ps.setBigDecimal(6, new BigDecimal(lessThan10 + .8));
      ps.setDouble(7, i + .88);
      ps.setFloat(8, i + .9f);
      ps.setBytes(9, ("B" + lessThan10).getBytes());
      ps.setBigDecimal(10, new BigDecimal(i));
      ps.setFloat(11, lessThan10 * 1111);
      ps.setShort(12, (short)i);
      ps.setTime(13, new Time(System.currentTimeMillis()));
      ps.setTimestamp(14, new Timestamp(System.currentTimeMillis()));
      ps.setString(15, "BY" + lessThan10);
      ps.setBytes(16, ("" + lessThan10).getBytes());
      ps.setClob(17, new StringReader("<xml><sometag>UPDATE XML CLOB " + i + "</sometag></xml>"));
      ps.setLong(18, i);
      ps.execute();
    }
  }
  
  private void clientCreateDiskStore(int clientId, String diskStoreName) throws Exception {
    clientSQLExecute(clientId, "CREATE DISKSTORE " + diskStoreName + "('" + diskStoreName + "') MAXLOGSIZE 2");
  }
  
  private void clientDropDiskStore(int clientId, String diskStoreName) throws Exception {
    clientSQLExecute(clientId, "DROP DISKSTORE " + diskStoreName);
  }
  
  private void clientCreatePersistentReplicateTable(int clientId, String tableName, String diskStoreName) throws Exception {
    String create = "CREATE TABLE " + tableName + "(bigIntegerField BIGINT, blobField BLOB(1K), charField CHAR(1)," +
        "charForBitData CHAR(1) FOR BIT DATA, clobField CLOB(1K), dateField DATE, decimalField DECIMAL(10,1)," +
        "doubleField DOUBLE, floatField FLOAT(10), longVarcharForBitDataField LONG VARCHAR FOR BIT DATA," +
        "numericField NUMERIC(10,1), realField REAL, smallIntField SMALLINT, timeField TIME, timestampField TIMESTAMP," +
        "varcharField VARCHAR(10), varcharForBitData VARCHAR(1) FOR BIT DATA, xmlField XML) REPLICATE persistent '" + diskStoreName + "'";
    clientSQLExecute(clientId, create);
  }
  
  private void clientCreateHDFSSTORE(final int clientId, final String hdfsStoreName) throws Exception {
    clientSQLExecute(clientId, "create hdfsstore "+ hdfsStoreName + " namenode 'localhost' homedir './myhdfs' queuepersistent true");
  }
  
  private void dropHDFSStore(final int clientId, final String hdfsStoreName) throws Exception {
    clientSQLExecute(clientId, "DROP HDFSSTORE IF EXISTS " + hdfsStoreName);
  }
  
  private void dropBookingTable(final int clientId) throws Exception {
    clientSQLExecute(1, "DROP TABLE IF EXISTS Booking");
  }
  
  private void clientCreateBookingTableWithHDFS(final int clientId, final String hdfsStoreName) throws Exception {
    clientSQLExecute(clientId, "CREATE TABLE Booking (id bigint not null, " + 
         "beds int not null, " +
         "primary key (id)) " +
         "PARTITION BY PRIMARY KEY " + 
         "PERSISTENT " +
         "HDFSSTORE (" +hdfsStoreName + ")");
  }
  
  private void insertRowsIntoBookingsTable(final int start , final int count) throws SQLException {
    final Connection conn = TestUtil.getConnection();
    Statement stmt =null;
    try {
      stmt = conn.createStatement();
      
      for (int i=0; i<count; i++) {
        stmt.executeUpdate("INSERT INTO BOOKING VALUES ("+ i+start + ",1)");
      }
    } finally {
      stmt.close();
    }
  }
  
  
  
  private void deleteRowsFromBookingsTable(final int start , final int count) throws SQLException {
    final Connection conn = TestUtil.getConnection();
    Statement stmt =null;
    try {
      stmt = conn.createStatement();
      
      for (int i=0; i<count; i++) {
        stmt.executeUpdate("DELETE FROM BOOKING WHERE ID= + " + i+start);
      }
    } finally {
      stmt.close();
    }
  }
  
  
  private void clientCreatePersistentPartitionedTable(int clientId, String tableName, String diskStoreName) throws Exception {
    String create = "CREATE TABLE " + tableName + "(bigIntegerField BIGINT, blobField BLOB(1K), charField CHAR(1)," +
        "charForBitData CHAR(1) FOR BIT DATA, clobField CLOB(1K), dateField DATE, decimalField DECIMAL(10,1)," +
        "doubleField DOUBLE, floatField FLOAT(10), longVarcharForBitDataField LONG VARCHAR FOR BIT DATA," +
        "numericField NUMERIC(10,1), realField REAL, smallIntField SMALLINT, timeField TIME, timestampField TIMESTAMP," +
        "varcharField VARCHAR(10), varcharForBitData VARCHAR(1) FOR BIT DATA, xmlField XML, " +
        "PRIMARY KEY(bigIntegerField)) PARTITION BY PRIMARY KEY persistent '" + diskStoreName +"'";
    clientSQLExecute(clientId, create);
  }
  
  private void clientDropTable(int clientId, String tableName) throws Exception {
    String drop = "DROP TABLE " + tableName;
    clientSQLExecute(clientId, drop);
  }
  
  private void clientCreateIndex(int clientId, String tableName) throws Exception {
    String index = "CREATE INDEX " + tableName + "_INDEX on " + tableName  + "(bigIntegerField)";
    clientSQLExecute(clientId, index);
  }
  
  private Runnable getCopyDataDictionary(final String testDirectory) {
    return new SerializableRunnable("copying data dictionary") {
      public void run() throws CacheException {
        try {
          copyDataDictionary(testDirectory + File.separator);
        }
        catch (Exception e) {
          throw new CacheException(e){};
        }
      }
    };
  }
  
  private void copyDataDictionary(String testDirectory) throws IOException {
    FileUtils.copyDirectory(new File(testDirectory, "datadictionary"), new File(testDirectory, copyDirectory + fileSeparator + "datadictionary") );
  }
  
  private Runnable getCopyOplogsRunnable(final String testDirectory, final String testDiskStoreName) {
    return new SerializableRunnable("copying Oplogs") {
      public void run() throws CacheException {
        try {
          copyOplogs(testDirectory, testDiskStoreName);
        }
        catch (Exception e) {
          throw new CacheException(e){};
        }
      }
    };
  }
  
  private void copyOplogs(String testDirectory, String diskStoreName) throws IOException {
    File copiedDirectory = new File(testDirectory, copyDirectory + File.separator + diskStoreName);
    FileUtils.copyDirectory(new File(testDirectory + File.separator + diskStoreName), copiedDirectory);
    
    String[] lk = copiedDirectory.list(new FilenameFilter() {
      @Override
      public boolean accept(File arg0, String arg1) {
        return arg1.endsWith("*.lk");
      }
    });
    if (lk != null) {
    //remove lock
      for (int i = 0; i < lk.length; i++) {
        try {
          FileUtils.forceDelete(new File(copiedDirectory, lk[i]));
        } catch (IOException ioe) {
          // ignore
        }
      }
    }
  }
  
  private Runnable getDeleteOplogsRunnable(final String testDirectory, final String testDiskStoreName, final String[] suffix) {
    return new SerializableRunnable("delete Oplogs") {
      public void run() throws CacheException {
        try {
          deleteOplogs(testDirectory, testDiskStoreName, suffix);
        }
        catch (Exception e) {
          throw new CacheException(e){};
        }
      }
    };
  }
  
  private void deleteOplogs(String testDirectory, String diskStoreName, final String[] suffix) throws IOException {
    File copiedDirectory = new File(testDirectory, copyDirectory + File.separator + diskStoreName);
    
    String[] deleteFiles = copiedDirectory.list(new FilenameFilter() {
      public boolean accept(File arg0, String arg1) {
        for (int i = 0; i < suffix.length; i++) {
          if (arg1.endsWith(suffix[i])) {
            return true;
          }
        }
        return false;
      }
    });
    
    for (int i = 0; i < deleteFiles.length; i++) {
      try {
        FileUtils.forceDelete(new File(copiedDirectory, deleteFiles[i]));
      } catch (IOException ioe) {
        // ignore
      }
    }
  }
  
  private Runnable getCorruptOplogsRunnable(final String testDirectory, final String testDiskStoreName, final String[] suffix, final int lengthToPreserve) {
    return new SerializableRunnable("corrupt Oplogs") {
      public void run() throws CacheException {
        try {
          corruptOplogs(testDirectory, testDiskStoreName, suffix, lengthToPreserve);
        }
        catch (Exception e) {
          throw new CacheException(e){};
        }
      }
    };
  }
  
  private Runnable getCorruptIfRunnable(final String testDirectory, final String testDiskStoreName) {
    return new SerializableRunnable("corrupt IF files") {
      public void run() throws CacheException {
        try {
          corruptOplogsWithRightShifting(testDirectory, testDiskStoreName, new String[] {".if"});
        }
        catch (Exception e) {
          throw new CacheException(e){};
        }
      }
    };
  }
  
  private void corruptOplogs(String testDirectory, String diskStoreName, final String[] suffix, final int lengthToPreserve) throws IOException {
    File copiedDirectory = new File(testDirectory, copyDirectory + File.separator + diskStoreName);
    String[] corruptFiles = copiedDirectory.list(new FilenameFilter() {
      public boolean accept(File arg0, String arg1) {
        for (int i = 0; i < suffix.length; i++) {
          if (arg1.endsWith(suffix[i])) {
            return true;
          }
        }
        return false;
      }
    });
    
    for (int i = 0; i < corruptFiles.length; i++) {
      GemFireXDDataExtractorJUnit.corruptFile(lengthToPreserve, new File(copiedDirectory, corruptFiles[i]));
      //corruptFile(new File(copiedDirectory, corruptFiles[i]));
    }
  }
  
  private void corruptOplogsWithRightShifting(String testDirectory, String diskStoreName, final String[] suffix) throws IOException {
    File copiedDirectory = new File(testDirectory, copyDirectory + File.separator + diskStoreName);
    String[] corruptFiles = copiedDirectory.list(new FilenameFilter() {
      public boolean accept(File arg0, String arg1) {
        for (int i = 0; i < suffix.length; i++) {
          if (arg1.endsWith(suffix[i])) {
            return true;
          }
        }
        return false;
      }
    });
    
    for (int i = 0; i < corruptFiles.length; i++) {
      corruptFile(new File(copiedDirectory, corruptFiles[i]));
    }
  }
  
  
  private Runnable getCheckDDLSizeRunnable(final String directory, final int size) {
    return new SerializableRunnable("check ddl size") {
      public void run() throws CacheException {
        try {
          checkDDLSize(directory, size);
        }
        catch (Exception e) {
          throw new CacheException(e){};
        }
      }
    };
  }
  
  
  
  public static void corruptFile(File file) throws IOException {
    byte[] fileBytes = FileUtils.readFileToByteArray(file);
    if (fileBytes != null && fileBytes.length > 0) {
      getGlobalLogger().info("#SB Corrupting the file : " + file.getCanonicalPath());

      for (int i =0; i<fileBytes.length; i++) {
        fileBytes[i] = (byte) (fileBytes[i]>>2);
      }
    }
    FileUtils.writeByteArrayToFile(file, fileBytes, false);
  }
  
  private void checkDDLSize(String testDirectory, int size) throws IOException {
    File copiedDirectory = new File(testDirectory, copyDirectory + File.separator + "datadictionary");
    String[] sql = copiedDirectory.list(new FilenameFilter() {
      public boolean accept(File arg0, String arg1) {
        return arg1.contains(".sql");
      }
    });
    
    
    List<String> ddlStatements = new ArrayList<String>();
    
    if (sql != null) {
      for (int i = 0; i < sql.length; i++) {
        ddlStatements.addAll(ExtractorUtils.readSqlStatements(new File(copiedDirectory, sql[i]).getName()));
      }
    } else {
      getLogWriter().info("Could not find the exported .sql file");
    }
    
    if (size != ddlStatements.size()) {
      throw new CacheException("ddl not the expected size of :" + size + ", instead was " + ddlStatements.size()){};
    }
  }
}
