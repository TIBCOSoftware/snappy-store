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

import java.io.*;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Time;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import com.pivotal.gemfirexd.Attribute;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.internal.tools.dataextractor.domain.ServerInfo;
import com.pivotal.gemfirexd.internal.tools.dataextractor.domain.ServerInfo.Builder;
import com.pivotal.gemfirexd.internal.tools.dataextractor.extractor.GemFireXDDataExtractorImpl;
import com.pivotal.gemfirexd.internal.tools.dataextractor.report.ReportGenerator;
import com.pivotal.gemfirexd.internal.tools.dataextractor.snapshot.GFXDSnapshotExportStat;
import com.pivotal.gemfirexd.internal.tools.dataextractor.utils.ExtractorUtils;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import org.apache.commons.io.FileUtils;
import org.apache.commons.io.FilenameUtils;

public class GemFireXDDataExtractorJUnit extends JdbcTestBase{

  private Connection connection;
  private Statement statement;
  private File ddCopy = new File("dd-copy");
  private File oplogCopy = new File("oplog-copy");
  private File outputDir = new File("export-dir");
  private String testDiskStoreName = "TESTDISKSTORE";
  
  public GemFireXDDataExtractorJUnit(String name) {
    super(name);
  }
  
  public void setUp() throws Exception {
    super.setUp();
    TestUtil.deletePersistentFiles = true;
    connection = super.getConnection();
    statement = connection.createStatement();
    outputDir.mkdirs();
    ddCopy.mkdirs();
    oplogCopy.mkdirs();
    EmbedConnection embeddedConnection = (EmbedConnection)connection;
    embeddedConnection.getTR().setupContextStack();
    this.deleteDirs = new String[0];
  }
  
  public void tearDown() throws Exception {
    connection.close();
    deleteAllTestOpLogs();
    TestUtil.shutDown();
    super.tearDown();
  }
  
  private void deleteAllTestOpLogs() throws Exception {
    try {
      FileUtils.forceDelete(oplogCopy);
    } catch (IOException ioe) {
      // ignore
    }
    try {
      FileUtils.forceDelete(ddCopy);
    } catch (IOException ioe) {
      // ignore
    }
    try {
      FileUtils.forceDelete(outputDir);
    } catch (IOException ioe) {
      // ignore
    }
  }
  
  public void testDDLExport() throws Exception {
    Map<ServerInfo, List<GFXDSnapshotExportStat>> hostToDdlMap = new HashMap<ServerInfo, List<GFXDSnapshotExportStat>>();
    int numDDL = 5;
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable("PERSIST_REPLICATE", testDiskStoreName);
    this.createPersistentPartitionedTable("PERSIST_PARTITIONED", testDiskStoreName);
    this.createIndex("PERSIST_REPLICATE");
    this.createIndex("PERSIST_PARTITIONED");
//    this.createReplicateTable();
//    this.createPartitionedTable();
//    this.createStoredProcedure();
    //create index;
    copyDataDictionary();
    String server1OutputDirPath = FilenameUtils.concat(outputDir.getCanonicalPath(), "s1");
    String server2OutputDirPath = FilenameUtils.concat(outputDir.getCanonicalPath(), "s2");
    File server1OutputDir = new File(server1OutputDirPath);
    File server2OutputDir = new File(server2OutputDirPath);
    
    server1OutputDir.mkdirs();
    server2OutputDir.mkdirs();
    
    
    List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportOfflineDDL(ddCopy.getCanonicalPath(), server1OutputDir.getCanonicalPath());
    
    GFXDSnapshotExportStat stat = stats.get(0);
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    List<String> ddls = ExtractorUtils.readSqlStatements(stat.getFileName());
    assertEquals(numDDL, ddls.size());
    
    Builder builder = new Builder();
    builder.serverName("s1");
    ServerInfo s1 = builder.build();
    hostToDdlMap.put(s1, stats);
    
    this.dropTable("PERSIST_REPLICATE");
    copyDataDictionary();
    stats = GemFireXDDataExtractorImpl.exportOfflineDDL(ddCopy.getCanonicalPath(), server2OutputDir.getCanonicalPath());
    
    
    builder = new Builder();
    builder.serverName("s2");
    ServerInfo s2 = builder.build();
    hostToDdlMap.put(s2, stats);
    
    
    //Check if the DDL are not grouped together as their content is not the same. 
    //Note that the persistent view is the same here. 
    List<List<GFXDSnapshotExportStat>> rankedDDLs = ReportGenerator.rankAndGroupDdlStats(hostToDdlMap);
    assertFalse(rankedDDLs.isEmpty());
    assertTrue(rankedDDLs.size() == 2);
    
  }
  
  public void testDDLSchemaChangeExport() throws Exception {
    int numDDL = 7;
    this.createSchema("firstSchema");
    this.setCurrentSchema("firstSchema");
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable("PERSIST_REPLICATE", testDiskStoreName);
    this.createPersistentPartitionedTable("PERSIST_PARTITIONED", testDiskStoreName);
    this.createIndex("PERSIST_REPLICATE");
    this.createIndex("PERSIST_PARTITIONED");
//    this.createReplicateTable();
//    this.createPartitionedTable();
//    this.createStoredProcedure();
    //create index;
    copyDataDictionary();
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportOfflineDDL(ddCopy.getCanonicalPath(), outputDir.getCanonicalPath());
    GFXDSnapshotExportStat stat = stats.get(0);
    List<String> ddls = ExtractorUtils.readSqlStatements(stat.getFileName());
    assertEquals(numDDL, ddls.size());
  }
  
  public void testNoDDLToExport() throws Exception {
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable("PERSIST_REPLICATE", testDiskStoreName);
    this.createPersistentPartitionedTable("PERSIST_PARTITIONED", testDiskStoreName);
    this.createIndex("PERSIST_REPLICATE");
    this.createIndex("PERSIST_PARTITIONED");
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    try {
    List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportOfflineDDL(ddCopy.getCanonicalPath(), outputDir.getCanonicalPath());
    }
    catch (IllegalStateException e) {
      return;
    }
    fail("expected an exception to be thrown due to missing ddl");
  }
  
  public void testCSVExportNoOplogsFound() throws Exception {
    String schemaName = "APP";
    String tableName = "REPLICATED_TABLE";
    String bucketName = null;
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 1000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    try {
      List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportDataOpLog(testDiskStoreName, oplogCopy.getCanonicalPath(), outputDir.getCanonicalPath(), false);
    }
    catch (IllegalStateException e) {
      return;
    }
    fail("should have received an exception due to no oplogs present");
  }
  
  public void testExportOnOnlineNode() throws Exception {
    String schemaName = "APP";
    String tableName = "REPLICATED_TABLE";
    String bucketName = null;
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 1000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    boolean ok = false;
    copyDataDictionary();
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    try {
      List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportDataOpLog(testDiskStoreName, "./" + testDiskStoreName, outputDir.getCanonicalPath(), false);
    }
    catch (Exception e) {
      //e.printStackTrace();
      ok = true;
    }
    if (!ok) {
      fail();
    }
  }

  public void testCSVExportSingleNodeReplicate() throws Exception {
    String schemaName = "APP";
    String tableName = "REPLICATED_TABLE";
    String bucketName = null;
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 1000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    copyOplogs();
    List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportDataOpLog(testDiskStoreName, oplogCopy.getCanonicalPath(), outputDir.getCanonicalPath(), false);
    GFXDSnapshotExportStat expectedStat = new GFXDSnapshotExportStat(schemaName, tableName, bucketName, null);
    expectedStat.setNumValuesDecoded(700);
    GFXDSnapshotExportStat stat = stats.get(0);
    assertEquals(1, stats.size());
    assertEquals(0, stat.getEntryDecodeErrors().size());
    assertTrue(areStatsEqual(expectedStat, stat));
    
    File csvFile = new File(stat.getFileName());
    assertTrue(csvFile.exists());
    assertEquals(expectedStat.getNumValuesDecoded(), numLines(csvFile));
  }
  
  public void testCSVExportSingleNodeReplicateDropTable() throws Exception {
    String schemaName = "APP";
    String tableName = "REPLICATE_TABLE";
    String dropTableName = "REPLICATE_DROP_TABLE";
    String bucketName = null;
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 1000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    this.createPersistentReplicateTable(dropTableName, testDiskStoreName);
    this.insertData(dropTableName, 0, 1000);
    this.updateData(dropTableName, 0, 300);
    this.deleteData(dropTableName, 300, 600);
    this.dropTable(dropTableName);
    
    copyDataDictionary();
    copyOplogs();
    
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportDataOpLog(testDiskStoreName, oplogCopy.getCanonicalPath(), outputDir.getCanonicalPath(), false);
    GFXDSnapshotExportStat expectedStat = new GFXDSnapshotExportStat(schemaName, tableName, bucketName, null);
    expectedStat.setNumValuesDecoded(700);
    GFXDSnapshotExportStat stat = stats.get(0);
    assertEquals(1, stats.size());
    assertEquals(0, stat.getEntryDecodeErrors().size());
    assertTrue(areStatsEqual(expectedStat, stat));
    
    File csvFile = new File(stat.getFileName());
    assertTrue(csvFile.exists());
    assertEquals(expectedStat.getNumValuesDecoded(), numLines(csvFile));  
  }
    
  //Disabled due to removing krf num values check
  public void testCSVExportSingleNodeReplicateOplogCrfCorrupt() throws Exception {
    String tableName = "REPLICATED_TABLE";
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 10000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    copyOplogs();

    Iterator<File> oplogIterator = FileUtils.iterateFiles(oplogCopy, new String[]{"crf"}, true);
    while (oplogIterator.hasNext()) {
      corruptFile(500, (File)oplogIterator.next());
    }
        
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    
    List<String> diskStoreList = new ArrayList<String>();
    diskStoreList.add(oplogCopy.getCanonicalPath());
    List<GFXDSnapshotExportStat> stats = extractor.extractDiskStores(testDiskStoreName, diskStoreList, outputDir.getCanonicalPath());
    GFXDSnapshotExportStat stat = stats.get(0);
    assertEquals(1, stats.size());
    assertTrue(stat.isCorrupt());
  }
  
  public void testCSVExportSingleNodeReplicateOplogCrfDelete() throws Exception {
    String tableName = "REPLICATED_TABLE";
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 10000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    copyOplogs();
    
    Iterator<File> oplogIterator = FileUtils.iterateFiles(oplogCopy, new String[]{"crf"}, true);
    while (oplogIterator.hasNext()) {
      this.deleteFile((File)oplogIterator.next());
    }
    
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    List<String> diskStoreList = new ArrayList<String>();
    diskStoreList.add(oplogCopy.getCanonicalPath());
    List<GFXDSnapshotExportStat> stats = extractor.extractDiskStores(testDiskStoreName, diskStoreList, outputDir.getCanonicalPath());
    assertEquals(0, stats.size());
  }
  
  public void DISABLEtestCSVExportSingleNodeReplicateOplogKrfCorrupt() throws Exception {
    String tableName = "REPLICATED_TABLE";
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 10000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    copyOplogs();
    
    Iterator<File> oplogIterator = FileUtils.iterateFiles(oplogCopy, new String[]{"krf"}, true);
    while (oplogIterator.hasNext()) {
      corruptFile(500, (File)oplogIterator.next());
    }
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    
    List<String> diskStoreList = new ArrayList<String>();
    diskStoreList.add(oplogCopy.getCanonicalPath());
    List<GFXDSnapshotExportStat> stats = extractor.extractDiskStores(testDiskStoreName, diskStoreList, outputDir.getCanonicalPath());
    GFXDSnapshotExportStat stat = stats.get(0);
    assertEquals(1, stats.size());
    assertTrue(stat.isCorrupt());
  }
  
  public void DISABLEtestCSVExportSingleNodeReplicateOplogKrfMissing() throws Exception {
    String tableName = "REPLICATED_TABLE";
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 10000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    copyOplogs();

    Iterator<File> oplogIterator = FileUtils.iterateFiles(oplogCopy, new String[]{"krf"}, true);
    while (oplogIterator.hasNext()) {
      this.deleteFile((File)oplogIterator.next());
    }
    
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    
    List<String> diskStoreList = new ArrayList<String>();
    diskStoreList.add(oplogCopy.getCanonicalPath());
    List<GFXDSnapshotExportStat> stats = extractor.extractDiskStores(testDiskStoreName, diskStoreList, outputDir.getCanonicalPath());
    GFXDSnapshotExportStat stat = stats.get(0);
    assertEquals(1, stats.size());
    //we actually can't detect if crf is missing any values if KRF is missing
//    assertTrue(stat.isCorrupt());
  }
  
  public void testCSVExportSingleNodeReplicateOplogDrfCorrupt() throws Exception {
    String tableName = "REPLICATED_TABLE";
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 10000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    copyOplogs();

    Iterator<File> oplogIterator = FileUtils.iterateFiles(oplogCopy, new String[]{"drf"}, true);
    while (oplogIterator.hasNext()) {
      corruptFile(21, (File)oplogIterator.next());
    }
    
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
        
    List<String> diskStoreList = new ArrayList<String>();
    diskStoreList.add(oplogCopy.getCanonicalPath());
    List<GFXDSnapshotExportStat> stats = extractor.extractDiskStores(testDiskStoreName, diskStoreList, outputDir.getCanonicalPath());
    //Corrupt DRF leads to no export and no stat and no recommendation
    assertEquals(0, stats.size());
  }
  
  public void testCSVExportSingleNodeReplicateOplogDrfMissing() throws Exception {
    addExpectedException("Unable to recover the following tables, possibly due to a missing .drf file:");
    String tableName = "REPLICATED_TABLE";
    this.createDiskStore(testDiskStoreName);
    this.createPersistentReplicateTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 10000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    copyOplogs();

    Iterator<File> oplogIterator = FileUtils.iterateFiles(oplogCopy, new String[]{"drf"}, true);
    while (oplogIterator.hasNext()) {
      this.deleteFile((File)oplogIterator.next());
    }
    
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
        
    List<String> diskStoreList = new ArrayList<String>();
    diskStoreList.add(oplogCopy.getCanonicalPath());
    List<GFXDSnapshotExportStat> stats = extractor.extractDiskStores(testDiskStoreName, diskStoreList, outputDir.getCanonicalPath());
    //Corrupt DRF leads to no export and no stat and no recommendation
    assertEquals(0, stats.size());
  }
  
  public void testCSVExportSingleNodePartitioned() throws Exception {
    String schemaName = "APP";
    String tableName = "PARTITIONED_TABLE";
    String bucketName = null;
    this.createDiskStore(testDiskStoreName);
    this.createPersistentPartitionedTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 1000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    
    copyDataDictionary();
    copyOplogs();
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    
    List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportDataOpLog(testDiskStoreName, oplogCopy.getCanonicalPath(), outputDir.getCanonicalPath(), false);
    GFXDSnapshotExportStat expectedStat = new GFXDSnapshotExportStat(schemaName, tableName, bucketName, null);
    expectedStat.setNumValuesDecoded(700);
    GFXDSnapshotExportStat stat = stats.get(0);
    //113 for number of default buckets
    assertEquals(113, stats.size());
    assertEquals(0, stat.getEntryDecodeErrors().size());
    
    //let's iterate through and total up the stats and make sure the counts are the same
    int total = 0;
    Iterator<GFXDSnapshotExportStat> listIterator = stats.iterator();
    while (listIterator.hasNext()) {
      total += listIterator.next().getNumValuesDecoded();
    }
    assertEquals(700, total);
  }
  
  public void testCSVExportSingleNodePartitionedDropTable() throws Exception {
    String schemaName = "APP";
    String tableName = "PARTITIONED_TABLE";
    String dropTableName = "PARTITIONED_DROP_TABLE";
    String bucketName = null;
    this.createDiskStore(testDiskStoreName);
    this.createPersistentPartitionedTable(tableName, testDiskStoreName);
    this.insertData(tableName, 0, 1000);
    this.updateData(tableName, 0, 300);
    this.deleteData(tableName, 300, 600);
    this.createPersistentPartitionedTable(dropTableName, testDiskStoreName);
    this.insertData(dropTableName, 0, 1000);
    this.updateData(dropTableName, 0, 300);
    this.deleteData(dropTableName, 300, 600);
    this.dropTable(dropTableName);
    
    copyDataDictionary();
    copyOplogs();
    
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    extractor.createTestConnection();
    extractor.retrieveAllRowFormatters();
    
    List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportDataOpLog(testDiskStoreName, oplogCopy.getCanonicalPath(), outputDir.getCanonicalPath(), false);
    GFXDSnapshotExportStat expectedStat = new GFXDSnapshotExportStat(schemaName, tableName, bucketName, null);
    expectedStat.setNumValuesDecoded(700);
    GFXDSnapshotExportStat stat = stats.get(0);
    //113 for number of default buckets
    assertEquals(113, stats.size());
    assertEquals(0, stat.getEntryDecodeErrors().size());
    
    //let's iterate through and total up the stats and make sure the counts are the same
    int total = 0;
    Iterator<GFXDSnapshotExportStat> listIterator = stats.iterator();
    while (listIterator.hasNext()) {
      total += listIterator.next().getNumValuesDecoded();
    }
    assertEquals(700, total);
  }
  
  public void testCSVExportSingleNodeNoIndexExportTable() throws Exception {
    try {
        String schemaName = "APP";
        String tableName = "REPLICATE_TABLE";
        String bucketName = null;
        System.setProperty( Attribute.PERSIST_INDEXES, "true");
        this.createDiskStore(testDiskStoreName);
        this.createPersistentReplicateTable(tableName, testDiskStoreName);
        this.createIndex(tableName);
        this.insertData(tableName, 0, 1000);
        //this.updateData(tableName, 0, 300);  //Due to an issue comparing xml fields with index
        this.deleteData(tableName, 300, 600);
       
        copyDataDictionary();
        copyOplogs();
        GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
        extractor.createTestConnection();
        extractor.retrieveAllRowFormatters();
        
        List<GFXDSnapshotExportStat> stats = GemFireXDDataExtractorImpl.exportDataOpLog(testDiskStoreName, oplogCopy.getCanonicalPath(), outputDir.getCanonicalPath(), false);
        GFXDSnapshotExportStat expectedStat = new GFXDSnapshotExportStat(schemaName, tableName, bucketName, null);
        expectedStat.setNumValuesDecoded(700);
        GFXDSnapshotExportStat stat = stats.get(0);
        assertEquals(1, stats.size());
        assertEquals(0, stat.getEntryDecodeErrors().size());
        assertTrue(areStatsEqual(expectedStat, stat));
    }
    finally {
      System.setProperty( Attribute.PERSIST_INDEXES, "false");
    }
  }
  
  public void testextractorArgs() {
    GemFireXDDataExtractorImpl extractor = new GemFireXDDataExtractorImpl();
    boolean exceptionThrown = false;
    try {
      extractor.consumeProperties();
      extractor.extract();
    } catch (Exception e) {
      exceptionThrown = true;
      //e.printStackTrace();
    }
    assertTrue(exceptionThrown);
    List<String> argsList = new ArrayList<String>();
    argsList.add(GemFireXDDataExtractorImpl.PROPERTY_FILE_ARG + "=" + "salvage.properties");
    try {
      extractor.processArgs(argsList.toArray(new String[argsList.size()]));
      extractor.consumeProperties();
      extractor.extract();
    } catch (Exception e) {
      assertTrue(e instanceof FileNotFoundException);
    }
    
    argsList.clear();
    String propFilePath = "salvage.properties";
    String logLevelString = "FINE";
    String logFilePath = "extractor.log";
    String delimiter = ",";
    String outputDirPath = "/kuwait1/salvage";
    String numThreads = "3";
    
    argsList.add(GemFireXDDataExtractorImpl.PROPERTY_FILE_ARG + "=" + propFilePath);
    argsList.add(GemFireXDDataExtractorImpl.LOG_LEVEL_OPT + "=" + logLevelString);
    argsList.add(GemFireXDDataExtractorImpl.LOG_FILE_OPT + "=" + logFilePath);
    argsList.add(GemFireXDDataExtractorImpl.STRING_DELIMITER + "=" + ",");
    argsList.add(GemFireXDDataExtractorImpl.EXTRACTOR_OUTPUT_DIR_OPT + "=" + outputDirPath);
    argsList.add(GemFireXDDataExtractorImpl.NUM_THREADS_OPT + "=" + numThreads);

    try {
      extractor.processArgs(argsList.toArray(new String[argsList.size()]));
      extractor.consumeProperties();
      assertEquals(extractor.getLogLevelString(), logLevelString);
      assertEquals(extractor.getStringDelimiter(), delimiter);
      System.out.println(extractor.getLogFilePath());
      assertTrue(extractor.getLogFilePath().contains(logFilePath));
      assertFalse(extractor.isShowHelp());
      assertFalse(extractor.isUseSingleDDL());
      assertFalse(extractor.isSalvageInServerDir());
      assertTrue(extractor.useOverrodeNumThreads());
      assertTrue(extractor.getUserNumThreads() == 3);
    } catch (Exception e) {
      e.printStackTrace();
    }
  }
//  public void testRecoverFromCompactedOplog() throws Exception {
//    String schemaName = "APP";
//    String tableName = "REPLICATED_TABLE";
//    String bucketName = null;
//    this.createDiskStoreAllowForceCompaction(testDiskStoreName);
//    this.createPersistentReplicateTable(tableName, testDiskStoreName);
//    this.insertData(tableName, 0, 20000);
//    this.updateData(tableName, 0, 300);
//    this.deleteData(tableName, 0, 20000);
//
//    copyDataDictionary();
//    copyOplogs();
//    for (DiskStoreImpl diskStore : Misc.getGemFireCache().listDiskStores()) {
//      System.out.println("Forcing compaction on :" + diskStore.getName() + " and returned:" + diskStore.forceCompaction());
//    }
//    
//    LocalRegion region = (LocalRegion)Misc.getRegionForTable(schemaName + "." + tableName, false);
//    assertTrue(region.getDiskStore().getAllowForceCompaction());
//    assertTrue(region.getDiskStore().forceCompaction());
//    
//    List<GFXDSnapshotExportStat> stats = GemFireXDDataextractor.exportDataOpLog(testDiskStoreName, oplogCopy.getCanonicalPath(), outputDir.getCanonicalPath(), false);
//    GFXDSnapshotExportStat expectedStat = new GFXDSnapshotExportStat(schemaName, tableName, bucketName, null);
//    expectedStat.setNumValuesDecoded(700);
//    GFXDSnapshotExportStat stat = stats.get(0);
//    assertEquals(1, stats.size());
//    assertEquals(0, stat.getEntryDecodeErrors().size());
//    assertTrue(areStatsEqual(expectedStat, stat));
//    
//    File csvFile = new File(stat.getFileName());
//    assertTrue(csvFile.exists());
//    assertEquals(expectedStat.getNumValuesDecoded(), numLines(csvFile));
//  }

  
//  /** Tests the report generation comparison with a faked 2 node system with a RR and PR**/
//  public void testReportGeneratorWithSameMockedStats() throws Exception {
//    String schemaName = "APP";
//    String table1Name = "TABLE_1";
//    String table2Name = "TABLE_2";
//    String table2Bucket1 = "_BUCKET_1";
//    
//    Map<String,List<GFXDSnapshotExportStat>> hostToDdlStatsMap = new HashMap<String,List<GFXDSnapshotExportStat>>();
//    Map<String,List<GFXDSnapshotExportStat>> hostToStatsMap = new HashMap<String,List<GFXDSnapshotExportStat>>();
//    
//    List<GFXDSnapshotExportStat> node1Stats = new ArrayList<GFXDSnapshotExportStat>();
//    List<GFXDSnapshotExportStat> node2Stats = new ArrayList<GFXDSnapshotExportStat>();
//    GFXDSnapshotExportStat node1Stat1 = new GFXDSnapshotExportStat(schemaName, table1Name, null, null);
//    node1Stat1.setNumValuesDecoded(100);
//    node1Stat1.setFileName("APP_TABLE_1.csv");
//    node1Stat1.setLastModifiedTime(100);
//    node1Stat1.setServerName("Node1");
//    node1Stat1.setTableType("RR");
//    node1Stats.add(node1Stat1);
//    
//    GFXDSnapshotExportStat node1Stat2 = new GFXDSnapshotExportStat(schemaName, table2Name, table2Bucket1, null);
//    node1Stat2.setNumValuesDecoded(100);
//    node1Stat2.setFileName("APP_TABLE_2_BUCKET_1.csv");
//    node1Stat2.setLastModifiedTime(100);
//    node1Stat2.setServerName("Node2");
//    node1Stat2.setTableType("PR");
//    node1Stats.add(node1Stat2);
//    
//    GFXDSnapshotExportStat node2Stat1 = new GFXDSnapshotExportStat(schemaName, table1Name, null, null);
//    node2Stat1.setNumValuesDecoded(100);
//    node2Stat1.setFileName("APP_TABLE_1.csv");
//    node2Stat1.setLastModifiedTime(100);
//    node2Stat1.setServerName("Node2");
//    node2Stat1.setTableType("RR");
//    node2Stats.add(node2Stat1);
//    
//    GFXDSnapshotExportStat node2Stat2 = new GFXDSnapshotExportStat(schemaName, table2Name, table2Bucket1, null);
//    node2Stat2.setNumValuesDecoded(100);
//    node2Stat2.setFileName("APP_TABLE_2_BUCKET_1.csv");
//    node2Stat2.setLastModifiedTime(100);
//    node2Stat2.setServerName("Node2");
//    node2Stat2.setTableType("PR");
//    node2Stats.add(node2Stat2);
//    
//    hostToStatsMap = new HashMap<String, List<GFXDSnapshotExportStat>>();
//    hostToStatsMap.put("Node1", node1Stats);
//    hostToStatsMap.put("Node2", node2Stats);
//    
//    ReportGenerator reporter = new ReportGenerator(hostToDdlStatsMap, hostToStatsMap);
//    Map<String, ReportGenerator.StatGroup> schemaTableNameToStatGroup =reporter.analyzeHostToStatsMap(hostToStatsMap, new ReportGenerator.TextFileCloseEnoughMatchComparator(), false);
//    assertEquals(2, schemaTableNameToStatGroup.size());
//    
//    //Assert that the stats were considered equal and tallied into one tally
//    Map<GFXDSnapshotExportStat, List<GFXDSnapshotExportStat>> talliedStats = schemaTableNameToStatGroup.get("APP_TABLE_1").getTalliedStats();
//    assertEquals(1, talliedStats.keySet().size());
//    
//    talliedStats = schemaTableNameToStatGroup.get("APP_TABLE_2__BUCKET_1").getTalliedStats();
//    assertEquals(1, talliedStats.keySet().size());
//    
//  }
//  
//  /** Tests the report generation comparison with a faked 2 node system with a RR and PR**/
//  public void testReportGeneratorWithDifferentMockedStats() throws Exception {
//    String schemaName = "APP";
//    String table1Name = "TABLE_1";
//    String table2Name = "TABLE_2";
//    String table2Bucket1 = "_BUCKET_1";
//    
//    Map<String,List<GFXDSnapshotExportStat>> hostToDdlStatsMap = new HashMap<String,List<GFXDSnapshotExportStat>>();
//    Map<String,List<GFXDSnapshotExportStat>> hostToStatsMap = new HashMap<String,List<GFXDSnapshotExportStat>>();
//    
//    List<GFXDSnapshotExportStat> node1Stats = new ArrayList<GFXDSnapshotExportStat>();
//    List<GFXDSnapshotExportStat> node2Stats = new ArrayList<GFXDSnapshotExportStat>();
//    GFXDSnapshotExportStat node1Stat1 = new GFXDSnapshotExportStat(schemaName, table1Name, null, null);
//    node1Stat1.setNumValuesDecoded(100);
//    node1Stat1.setFileName("APP_TABLE_1.csv");
//    node1Stat1.setLastModifiedTime(100);
//    node1Stat1.setServerName("Node1");
//    node1Stat1.setTableType("RR");
//    node1Stats.add(node1Stat1);
//    
//    GFXDSnapshotExportStat node1Stat2 = new GFXDSnapshotExportStat(schemaName, table2Name, table2Bucket1, null);
//    node1Stat2.setNumValuesDecoded(100);
//    node1Stat2.setFileName("APP_TABLE_2_BUCKET_1.csv");
//    node1Stat2.setLastModifiedTime(100);
//    node1Stat2.setServerName("Node2");
//    node1Stat2.setTableType("PR");
//    node1Stats.add(node1Stat2);
//    
//    GFXDSnapshotExportStat node2Stat1 = new GFXDSnapshotExportStat(schemaName, table1Name, null, null);
//    node2Stat1.setNumValuesDecoded(50);
//    node2Stat1.setFileName("APP_TABLE_1.csv");
//    node2Stat1.setLastModifiedTime(100);
//    node2Stat1.setServerName("Node2");
//    node2Stat1.setTableType("RR");
//    node2Stats.add(node2Stat1);
//    
//    GFXDSnapshotExportStat node2Stat2 = new GFXDSnapshotExportStat(schemaName, table2Name, table2Bucket1, null);
//    node2Stat2.setNumValuesDecoded(50);
//    node2Stat2.setFileName("APP_TABLE_2_BUCKET_1.csv");
//    node2Stat2.setLastModifiedTime(100);
//    node2Stat2.setServerName("Node2");
//    node2Stat2.setTableType("PR");
//    node2Stats.add(node2Stat2);
//    
//    hostToStatsMap = new HashMap<String, List<GFXDSnapshotExportStat>>();
//    hostToStatsMap.put("Node1", node1Stats);
//    hostToStatsMap.put("Node2", node2Stats);
//    
//    ReportGenerator reporter = new ReportGenerator(hostToDdlStatsMap, hostToStatsMap);
//    Map<String, ReportGenerator.StatGroup> schemaTableNameToStatGroup =reporter.analyzeHostToStatsMap(hostToStatsMap, new ReportGenerator.TextFileCloseEnoughMatchComparator(), false);
//    assertEquals(2, schemaTableNameToStatGroup.size());
//    
//    //Assert that the stats were considered equal and tallied into one tally
//    Map<GFXDSnapshotExportStat, List<GFXDSnapshotExportStat>> talliedStats = schemaTableNameToStatGroup.get("APP_TABLE_1").getTalliedStats();
//    assertEquals(2, talliedStats.keySet().size());
//    
//    talliedStats = schemaTableNameToStatGroup.get("APP_TABLE_2__BUCKET_1").getTalliedStats();
//    assertEquals(2, talliedStats.keySet().size());
//  }
  
 
  
  private void copyDataDictionary() throws IOException {
    FileUtils.copyDirectory(new File("./datadictionary"), ddCopy);
  }
  
  private void copyOplogs() throws IOException {
    copyOplogs("./" + testDiskStoreName);
  }
  
  private void copyOplogs(String diskStoreLocation) throws IOException {
    FileUtils.copyDirectory(new File(diskStoreLocation), oplogCopy);
  }
  
  private boolean areStatsEqual(GFXDSnapshotExportStat expectedStat, GFXDSnapshotExportStat stat) throws Exception {
    if (expectedStat.getNumValuesDecoded() != stat.getNumValuesDecoded()) {
      throw new Exception("Expected values decoded:" + expectedStat.getNumValuesDecoded() + " but was " + stat.getNumValuesDecoded());
    }
    if (!expectedStat.getSchemaTableName().equals(stat.getSchemaTableName())) {
      throw new Exception("Expected schema/table name:" + expectedStat.getSchemaTableName() + " but was " + stat.getSchemaTableName());
    }
    return true;
  }
  
  static int numLines(File csvFile) throws IOException {
    int numLines = 0;
    BufferedReader reader = new BufferedReader(new FileReader(csvFile));
    String line = reader.readLine();
    while (line != null) {
      numLines++;
      line = reader.readLine();
    }
    return numLines;
  }
  
  public static void corruptFile(int sizeToCopy, File file) throws IOException {
    File parent = file.getParentFile();
    File corruptFile = new File(parent, "corruptOplog");
    RandomAccessFile raf = null;
    FileOutputStream fos = null;
    try {
      raf = new RandomAccessFile(file, "r");  
      byte[] bytesRead = new byte[sizeToCopy];  
      long remainingSize = file.length() - sizeToCopy;
      raf.read(bytesRead);    
      byte[] randomStuff = new byte[(int)remainingSize];
      for (int i = 0; i < remainingSize; i++) {
        randomStuff[i] = 1;
      }
      fos = new FileOutputStream(corruptFile);  
      fos.write(bytesRead);
      fos.write(randomStuff);
    }
    finally {
      fos.close();
      raf.close();
    }
    try {
      FileUtils.forceDelete(file);
    } catch (IOException ioe) {
      // ignore
    }
    corruptFile.renameTo(file);
  }
  
  private void deleteFile(File file) throws IOException {
    try {
      FileUtils.forceDelete(file);
    } catch (IOException ioe) {
      // ignore
    }
  }
  

  
  private void insertData(String tableName, int startIndex, int endIndex) throws SQLException{
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
    PreparedStatement ps = connection.prepareStatement("delete from " + tableName +" where bigIntegerField = ?");
    for (int i = startIndex; i < endIndex; i++) {
      ps.setLong(1, i);
      ps.execute();      
    }
  }
  
  private void updateData(String tableName, int startIndex, int endIndex) throws SQLException {
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
  
  private void createSchema(String schemaName) throws SQLException {
    statement.execute("CREATE SCHEMA " + schemaName);
  }
  
  private void setCurrentSchema(String schemaName) throws SQLException {
    statement.execute("SET CURRENT SCHEMA " + schemaName);
  }
  
  private void createDiskStore(String diskStoreName) throws SQLException {
    String createDiskStore = "CREATE DISKSTORE " + diskStoreName + "('" + diskStoreName + "') MAXLOGSIZE 1";
    String[] newDeleteDirs = new String[this.deleteDirs.length + 1];
    for (int i = 0 ; i < this.deleteDirs.length; i++) {
      newDeleteDirs[i] = this.deleteDirs[i];
    }
    newDeleteDirs[this.deleteDirs.length] = "./" + diskStoreName;
    this.deleteDirs = newDeleteDirs;
    statement.execute(createDiskStore);
  }
  
  private void createDiskStoreAllowForceCompaction(String diskStoreName) throws SQLException {
    String createDiskStore = "CREATE DISKSTORE " + diskStoreName + " ALLOWFORCECOMPACTION TRUE  COMPACTIONTHRESHOLD  1('" + diskStoreName + "') MAXLOGSIZE 1";
    String[] newDeleteDirs = new String[this.deleteDirs.length + 1];
    for (int i = 0 ; i < this.deleteDirs.length; i++) {
      newDeleteDirs[i] = this.deleteDirs[i];
    }
    newDeleteDirs[this.deleteDirs.length] = "./" + diskStoreName;
    this.deleteDirs = newDeleteDirs;
    statement.execute(createDiskStore);
  }
  
  private void createPersistentReplicateTable(String tableName, String diskStoreName) throws SQLException {
    String create = "CREATE TABLE " + tableName + "(bigIntegerField BIGINT, blobField BLOB(1K), charField CHAR(1)," +
        "charForBitData CHAR(1) FOR BIT DATA, clobField CLOB(1K), dateField DATE, decimalField DECIMAL(10,1)," +
        "doubleField DOUBLE, floatField FLOAT(10), longVarcharForBitDataField LONG VARCHAR FOR BIT DATA," +
        "numericField NUMERIC(10,1), realField REAL, smallIntField SMALLINT, timeField TIME, timestampField TIMESTAMP," +
        "varcharField VARCHAR(10), varcharForBitData VARCHAR(1) FOR BIT DATA, xmlField XML) REPLICATE persistent '" + diskStoreName + "'";
    statement.execute(create);
  }
  
  private void createPersistentPartitionedTable(String tableName, String diskStoreName) throws SQLException {
    String create = "CREATE TABLE " + tableName + "(bigIntegerField BIGINT, blobField BLOB(1K), charField CHAR(1)," +
            "charForBitData CHAR(1) FOR BIT DATA, clobField CLOB(1K), dateField DATE, decimalField DECIMAL(10,1)," +
            "doubleField DOUBLE, floatField FLOAT(10), longVarcharForBitDataField LONG VARCHAR FOR BIT DATA," +
            "numericField NUMERIC(10,1), realField REAL, smallIntField SMALLINT, timeField TIME, timestampField TIMESTAMP," +
            "varcharField VARCHAR(10), varcharForBitData VARCHAR(1) FOR BIT DATA, xmlField XML, " +
            "PRIMARY KEY(bigIntegerField)) PARTITION BY PRIMARY KEY persistent '" + diskStoreName +"'";
    statement.execute(create);
  }
  
  
  private void dropTable(String tableName) throws SQLException {
    String drop = "DROP TABLE " + tableName;
    statement.execute(drop);
  }
  
  private void createIndex(String tableName) throws SQLException {
    String index = "CREATE INDEX " + tableName + "_INDEX on " + tableName  + "(bigIntegerField)";
    statement.execute(index);
  }
  
  
//  public static EmbedConnection getextractorConnection() throws SQLException {
//    GemFireXDDataextractor extractor = new GemFireXDDataextractor();
//    extractor.loadDriver("com.pivotal.gemfirexd.jdbc.EmbeddedDriver");
//    Connection conn = extractor.getConnection("jdbc:gemfirexd:", extractor.createTestConnectionProperties());
//    return  (EmbedConnection)conn;
//  }

}
