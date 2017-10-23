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
package com.pivotal.gemfirexd.ddl;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;

import com.gemstone.gemfire.cache.CacheException;
import com.gemstone.gemfire.cache.hdfs.internal.HDFSStoreImpl;
import com.gemstone.gemfire.cache.hdfs.internal.hoplog.DDLHoplogOrganizer;
import com.gemstone.gemfire.internal.util.BlobHelper;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.DDLConflatable;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.iapi.sql.conn.LanguageConnectionContext;
import com.pivotal.gemfirexd.internal.impl.sql.compile.StatementNode;
import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;

/**
 * 
 * @author hemantb
 *
 */
public class AlterHDFSStoreDUnit extends DistributedSQLTestBase {
  
  public AlterHDFSStoreDUnit(String name) {
    super(name);
  }
 
  /**
   * Test distribution of Alter HDFS Store from client to servers
   * 
   * @throws Exception
   */
  public void testDistributionOfHDFSStoreAlter() throws Exception {

    // Start one client a two servers
    startVMs(1, 2);

    final File homeDirFile = new File(".", "myhdfs");
    final String homeDir = homeDirFile.getAbsolutePath();

    checkDirExistence(homeDir);
    clientSQLExecute(1, "create hdfsstore TEST namenode 'localhost' homedir '" +
        homeDir + "'");

    // Test the HDFSStore presence by using SYS.SYSHDFSSTORES
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select NAME, MAXWRITEONLYFILESIZE, WRITEONLYFILEROLLOVERINTERVALSECS,  MAXINPUTFILECOUNT , " +
          " MAXINPUTFILESIZE, MININPUTFILECOUNT, MINORCOMPACT, MAJORCOMPACTIONINTERVALMINS ," +
          " MAJORCOMPACT, PURGEINTERVALMINS ,BATCHSIZE, BATCHTIMEINTERVALMILLIS, MINORCOMPACTIONTHREADS, " +
          " MAJORCOMPACTIONTHREADS from SYS.SYSHDFSSTORES " +
          "WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "ddl-original");
    
    clientSQLExecute(1, "alter hdfsstore TEST "
        + "SET MaxWriteOnlyFileSize 47 "
        + "SET WriteOnlyFileRolloverInterval 347 minutes "
        + "SET MaxInputFileCount 98 "
        + "SET MaxInputFileSize 38 "
        + "SET MinorCompactionThreads 35 "
        + "SET MinInputFileCount 24 "
        + "SET MinorCompact false "
        + "SET MajorCompactionInterval 372 minutes "
        + "SET MajorCompactionThreads 29 "
        + "SET MajorCompact false "
        + "SET PurgeInterval 2392 minutes "
        + "SET BatchSize 23 "
        + "SET batchtimeinterval 2 seconds "
        );
    
    // No HDFS Store on client
    clientExecute(1, verifyNoHDFSStoreExistence("TEST"));
    
    // check the HDFS Store by GemFire Cache
    SerializableRunnable verifier = verifyHDFSStoreExistence("TEST");
    for (int i = 1; i < 3; ++i) {
      serverExecute(i, verifier);
    }
    
    // Test the HDFSStore presence on servers by using SYS.SYSHDFSSTORES
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2 },
        "select NAME, MAXWRITEONLYFILESIZE, WRITEONLYFILEROLLOVERINTERVALSECS,  MAXINPUTFILECOUNT , " +
          " MAXINPUTFILESIZE, MININPUTFILECOUNT, MINORCOMPACT, MAJORCOMPACTIONINTERVALMINS ," +
          " MAJORCOMPACT, PURGEINTERVALMINS ,BATCHSIZE, BATCHTIMEINTERVALMILLIS, MINORCOMPACTIONTHREADS, " +
          " MAJORCOMPACTIONTHREADS from SYS.SYSHDFSSTORES " +
          "WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "ddl-altered");
      
    // add one server and check the HDFS store existence by SYS.SYSHDFSSTORES
    startVMs(0, 1);

    sqlExecuteVerify(null, new int[] { 3 },
        "select NAME, MAXWRITEONLYFILESIZE, WRITEONLYFILEROLLOVERINTERVALSECS,  MaxInputFileCount , " +
            " MaxInputFileSize, MinInputFileCount, MINORCOMPACT, MAJORCOMPACTIONINTERVALMINS ," +
            " MAJORCOMPACT, PURGEINTERVALMINS ,BatchSize, BATCHTIMEINTERVALMILLIS, MINORCOMPACTIONTHREADS, " +
            " MAJORCOMPACTIONTHREADS from SYS.SYSHDFSSTORES " +
          "WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "ddl-altered");
    serverExecute(3, verifier);
    
    // check HDFS Store on all three servers by SYS.SYSHDFSSTORES
    sqlExecuteVerify(new int[] { 1 }, new int[] { 1, 2, 3 },
        "select NAME, MAXWRITEONLYFILESIZE, WRITEONLYFILEROLLOVERINTERVALSECS,  MaxInputFileCount , " +
            " MaxInputFileSize, MinInputFileCount, MINORCOMPACT, MAJORCOMPACTIONINTERVALMINS ," +
            " MAJORCOMPACT, PURGEINTERVALMINS ,BatchSize, BATCHTIMEINTERVALMILLIS, MINORCOMPACTIONTHREADS," +
            " MAJORCOMPACTIONTHREADS from SYS.SYSHDFSSTORES " +
          "WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
        "ddl-altered");
    
    clientSQLExecute(1, "drop hdfsstore TEST ");
    delete(homeDirFile);
  }
  
  /**
   * Test whether DDLs are persisted on HDFS when fired from accessor
   * 
   * @throws Exception
   */
  public void testDDLPersistenceFromClientOnMultipleServers() throws Exception {

    // Start one client a two servers
    startVMs(2, 3);

    final File homeDirFile1 = new File(".", "myhdfsfromclient");
    final String homeDir1 = homeDirFile1.getAbsolutePath();
    final File homeDirFile2 = new File(".", "myhdfsfromclient1");
    final String homeDir2 = homeDirFile2.getAbsolutePath();

    checkDirExistence(homeDir1);
    checkDirExistence(homeDir2);
    clientSQLExecute(1, "create hdfsstore test namenode 'localhost' homedir '" +
        homeDir1 + "'");
    clientSQLExecute(1, "create table mytab (col1 int primary key, col2 int) persistent hdfsstore (test)");
    clientSQLExecute(1, "create hdfsstore test1 namenode 'localhost' homedir '" +
        homeDir2 + "'");
    clientSQLExecute(1, "create table mytab1 (col1 int primary key, col2 int) persistent hdfsstore (test1)");

    //add a new server
    clientSQLExecute(1, "alter hdfsstore TEST "
        + "SET MaxWriteOnlyFileSize 47 "
        + "SET WriteOnlyFileRolloverInterval 347 minutes "
        + "SET MaxInputFileCount 98 "
        + "SET MaxInputFileSize 38 "
        + "SET MinorCompactionThreads 35 "
        + "SET MinInputFileCount 24 "
        + "SET MinorCompact false "
        + "SET MajorCompactionInterval 372 minutes "
        + "SET MajorCompactionThreads 29 "
        + "SET MajorCompact false "
        + "SET PurgeInterval 2392 minutes "
        + "SET BatchSize 23 "
        + "SET batchtimeinterval 2 seconds "
        );
    
    // Test if the alter hdfs store is persisted in ddlhop 
    // of only the relevant HDFS Store
    serverExecute(1, verifyDDLPersistenceForTest());
    serverExecute(2, verifyDDLPersistenceForTest());
    serverExecute(3, verifyDDLPersistenceForTest());
    
    serverExecute(1, verifyDDLPersistenceForTest1());
    serverExecute(2, verifyDDLPersistenceForTest1());
    serverExecute(3, verifyDDLPersistenceForTest1());
    
    
    clientSQLExecute(1, "drop table mytab1 ");
    clientSQLExecute(1, "drop table mytab ");
    clientSQLExecute(1, "drop hdfsstore test ");
    clientSQLExecute(1, "drop hdfsstore test1 ");
    // the store should be deleted 
    sqlExecuteVerify(new int[] { 1 , 2}, null,
        "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
        TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "empty");
    
    delete(homeDirFile1);
    delete(homeDirFile2);
  }
 
  /**
   * Test whether DDLs are persisted on HDFS when fired from server
   * @throws Exception
   */
 public void testDDLPersistenceFromServer() throws Exception {

   // Start one client a two servers
   startVMs(2, 3);

   final File homeDirFile1 = new File(".", "myhdfsfromclient");
   final String homeDir1 = homeDirFile1.getAbsolutePath();
   final File homeDirFile2 = new File(".", "myhdfsfromclient1");
   final String homeDir2 = homeDirFile2.getAbsolutePath();

   checkDirExistence(homeDir1);
   checkDirExistence(homeDir2);
   serverSQLExecute(1, "create hdfsstore test namenode 'localhost' homedir '" +
       homeDir1 + "'");
   serverSQLExecute(1, "create table mytab (col1 int primary key, col2 int) persistent hdfsstore (test)");
   serverSQLExecute(1, "create hdfsstore test1 namenode 'localhost' homedir '" +
       homeDir2 + "'");
   serverSQLExecute(1, "create table mytab1 (col1 int primary key, col2 int) persistent hdfsstore (test1)");

   //add a new server
   serverSQLExecute(1, "alter hdfsstore TEST "
       + "SET MaxWriteOnlyFileSize 47 "
       + "SET WriteOnlyFileRolloverInterval 347 minutes "
       + "SET MaxInputFileCount 98 "
       + "SET MaxInputFileSize 38 "
       + "SET MinorCompactionThreads 35 "
       + "SET MinInputFileCount 24 "
       + "SET MinorCompact false "
       + "SET MajorCompactionInterval 372 minutes "
       + "SET MajorCompactionThreads 29 "
       + "SET MajorCompact false "
       + "SET PurgeInterval 2392 minutes "
       + "SET BatchSize 23 "
       + "SET batchtimeinterval 2 seconds "
       );
   
   
   serverExecute(1, verifyDDLPersistenceForTest());
   serverExecute(2, verifyDDLPersistenceForTest());
   serverExecute(3, verifyDDLPersistenceForTest());
   
   serverExecute(1, verifyDDLPersistenceForTest1());
   serverExecute(2, verifyDDLPersistenceForTest1());
   serverExecute(3, verifyDDLPersistenceForTest1());
   
   // check HDFS Store on all three servers  and clients by SYS.SYSHDFSSTORES
   sqlExecuteVerify(new int[] { 1 , 2}, new int[] { 1, 2, 3 },
       "select NAME, MAXWRITEONLYFILESIZE, WRITEONLYFILEROLLOVERINTERVALSECS,  MaxInputFileCount , " +
           " MaxInputFileSize, MinInputFileCount, MINORCOMPACT, MAJORCOMPACTIONINTERVALMINS ," +
           " MAJORCOMPACT, PURGEINTERVALMINS ,BatchSize, BATCHTIMEINTERVALMILLIS, MINORCOMPACTIONTHREADS," +
           " MAJORCOMPACTIONTHREADS from SYS.SYSHDFSSTORES " +
         "WHERE NAME = 'TEST' ",
       TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
       "ddl-altered");
   
   serverSQLExecute(1, "drop table mytab1 ");
   serverSQLExecute(1, "drop table mytab ");
   serverSQLExecute(1, "drop hdfsstore test ");
   serverSQLExecute(1, "drop hdfsstore test1 ");
   // the store should be deleted 
   sqlExecuteVerify(new int[] { 1 , 2}, null,
       "select NAME from SYS.SYSHDFSSTORES WHERE NAME = 'TEST' ",
       TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml", "empty");
   
   delete(homeDirFile1);
   delete(homeDirFile2);
 }
 
 /**
  * Test if Alter ddl is available after a restart
  * @throws Exception
  */
 public void testRestart() throws Exception {
     // Start one client a two servers
     startVMs(1, 1);

     final File homeDirFile = new File(".", "myhdfs");
     final String homeDir = homeDirFile.getAbsolutePath();

     checkDirExistence(homeDir);
     clientSQLExecute(1, "create hdfsstore TEST namenode 'localhost'  homedir '" +
         homeDir + "'");

     clientSQLExecute(1, "alter hdfsstore TEST "
         + "SET MaxWriteOnlyFileSize 47 "
         + "SET WriteOnlyFileRolloverInterval 347 minutes "
         + "SET MaxInputFileCount 98 "
         + "SET MaxInputFileSize 38 "
         + "SET MinorCompactionThreads 35 "
         + "SET MinInputFileCount 24 "
         + "SET MinorCompact false "
         + "SET MajorCompactionInterval 372 minutes "
         + "SET MajorCompactionThreads 29 "
         + "SET MajorCompact false "
         + "SET PurgeInterval 2392 minutes "
         + "SET BatchSize 23 "
         + "SET batchtimeinterval 2 seconds "
         );
     
     //shutdown and restart
     stopAllVMs();    
     restartVMNums(-1);
     restartVMNums(1);
     
     // No HDFS Store on client
     clientExecute(1, verifyNoHDFSStoreExistence("TEST"));
     
     // check the HDFS Store by GemFire Cache
     SerializableRunnable verifier = verifyHDFSStoreExistence("TEST");
     serverExecute(1, verifier);
     
     // Test the HDFSStore presence on servers by using SYS.SYSHDFSSTORES
     sqlExecuteVerify(new int[] { 1 }, new int[] { 1 },
         "select NAME, MAXWRITEONLYFILESIZE, WRITEONLYFILEROLLOVERINTERVALSECS,  MAXINPUTFILECOUNT , " +
           " MAXINPUTFILESIZE, MININPUTFILECOUNT, MINORCOMPACT, MAJORCOMPACTIONINTERVALMINS ," +
           " MAJORCOMPACT, PURGEINTERVALMINS ,BATCHSIZE, BATCHTIMEINTERVALMILLIS, MINORCOMPACTIONTHREADS, " +
           " MAJORCOMPACTIONTHREADS from SYS.SYSHDFSSTORES " +
           "WHERE NAME = 'TEST' ",
         TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
         "ddl-altered"); 
     clientSQLExecute(1, "drop hdfsstore TEST ");
     
     delete(homeDirFile);
   }
 
  /**
  * Simulate a rollback initiated by a single server and ensure that 
  * rollback has happened on all servers and all clients 
  * @throws Exception
  */
   public void testRollback() throws Exception {
     // Start one client a two servers
     startVMs(1, 3);

     final File homeDirFile = new File(".", "myhdfs");
     final String homeDir = homeDirFile.getAbsolutePath();

     checkDirExistence(homeDir);
     clientSQLExecute(1, "create hdfsstore TEST namenode 'localhost'  homedir '" +
         homeDir + "'");

     setupObservers(serverVMs.get(2));
     boolean exception = false; 
     addExpectedException(null, new int[] { 3 },
         java.sql.SQLNonTransientConnectionException.class);
     try  {
       serverSQLExecute(1, "alter hdfsstore TEST "
           + "SET MaxWriteOnlyFileSize 47 "
           + "SET WriteOnlyFileRolloverInterval 347 minutes "
           + "SET MaxInputFileCount 98 "
           + "SET MaxInputFileSize 38 "
           + "SET MinorCompactionThreads 35 "
           + "SET MinInputFileCount 24 "
           + "SET MinorCompact false "
           + "SET MajorCompactionInterval 372 minutes "
           + "SET MajorCompactionThreads 29 "
           + "SET MajorCompact false "
           + "SET PurgeInterval 2392 minutes "
           + "SET BatchSize 23 "
           + "SET batchtimeinterval 2 seconds "
           );
       
     } catch (Exception e) {
       exception = true; 
     }
     removeExpectedException(null, new int[] { 3 },
         java.sql.SQLNonTransientConnectionException.class);
     
     assertTrue("Exception should have been thrown from the server sql execute " , exception);
     clearObservers(serverVMs.get(2));
     
     // No HDFS Store on client
     clientExecute(1, verifyNoHDFSStoreExistence("TEST"));
     
     // check the HDFS Store by GemFire Cache
     SerializableRunnable verifier = verifyHDFSStoreExistence("TEST", true);
     serverExecute(1, verifier);
     serverExecute(2, verifier);
     serverExecute(3, verifier);
     
     // Test the HDFSStore presence on servers by using SYS.SYSHDFSSTORES
     sqlExecuteVerify(new int[] { 1 }, new int[] { 1,2,3 },
         "select NAME, MAXWRITEONLYFILESIZE, WRITEONLYFILEROLLOVERINTERVALSECS,  MAXINPUTFILECOUNT , " +
           " MAXINPUTFILESIZE, MININPUTFILECOUNT, MINORCOMPACT, MAJORCOMPACTIONINTERVALMINS ," +
           " MAJORCOMPACT, PURGEINTERVALMINS ,BATCHSIZE, BATCHTIMEINTERVALMILLIS, MINORCOMPACTIONTHREADS, " +
           " MAJORCOMPACTIONTHREADS from SYS.SYSHDFSSTORES " +
           "WHERE NAME = 'TEST' ",
         TestUtil.getResourcesDir() + "/lib/checkHDFSStore.xml",
         "ddl-original"); 
     clientSQLExecute(1, "drop hdfsstore TEST ");

     delete(homeDirFile);
   }
   
   /**
    * Test alter for non existent HDFSStore
    * @throws Exception
    */
   public void testAlterForNonExistentHDFSStore() throws Exception { 
     // Start one client a two servers
     startVMs(1, 2);

     final File homeDirFile = new File(".", "myhdfs");
     final String homeDir = homeDirFile.getAbsolutePath();

     checkDirExistence(homeDir);
     clientSQLExecute(1, "create hdfsstore TEST namenode 'localhost' homedir '" +
         homeDir + "'");
     boolean exception = false;
     try  {
       clientSQLExecute(1, "alter hdfsstore TEST1 "
             + "SET MaxWriteOnlyFileSize 47 "
           );
     } catch (java.sql.SQLSyntaxErrorException e) {
       exception = true;
     }
     assertTrue("alter command for a non existent HDFSStore should have failed", exception);
     exception = false;
     try  {
       serverSQLExecute(1, "alter hdfsstore TEST1 "
             + "SET MaxWriteOnlyFileSize 47 "
           );
     } catch (Exception e) {
       exception = true;
     }
     assertTrue("alter command for a non existent HDFSStore should have failed", exception);
     clientSQLExecute(1, "drop hdfsstore TEST ");
     delete(homeDirFile);
   }
   
   private SerializableRunnable verifyDDLPersistenceForTest1() {
     return new SerializableRunnable() {
       @Override
       public void run() {
         ArrayList<DDLConflatable> ddlconflatables = null;
         
         try {
           HDFSStoreImpl hdfsStore = Misc.getGemFireCache().findHDFSStore("TEST1");
           ddlconflatables = getDDLConflatables(hdfsStore);
         } catch (Exception e) {
           Misc.getGemFireCache().getLoggerI18n().fine("EXCEPTION " + e);
         }
         
         assertTrue(ddlconflatables.size() == 2);
         
         assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create hdfsstore"));
         assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create table"));
  
         
       }
      
     };
   }
   private SerializableRunnable verifyDDLPersistenceForTest() {
     return new SerializableRunnable() {
       @Override
       public void run() {
         ArrayList<DDLConflatable> ddlconflatables = null;
         
         try {
           HDFSStoreImpl hdfsStore = Misc.getGemFireCache().findHDFSStore("TEST");
           ddlconflatables = getDDLConflatables(hdfsStore);
         } catch (Exception e) {
           getLogWriter().warn("EXCEPTION " + e);
         }
         assertEquals("Unexpected DDLs: " + ddlconflatables,
             3, ddlconflatables.size());
         assertTrue(ddlconflatables.get(0).getValueToConflate().startsWith("create hdfsstore"));
         assertTrue(ddlconflatables.get(1).getValueToConflate().startsWith("create table"));
         assertTrue(ddlconflatables.get(2).getValueToConflate().startsWith("alter hdfsstore"));
       }
     };
   }
   private ArrayList<DDLConflatable> getDDLConflatables(HDFSStoreImpl store) throws IOException,
   ClassNotFoundException {
     DDLHoplogOrganizer organizer = store.getDDLHoplogOrganizer();
     
     ArrayList<byte[]> ddls = organizer.getDDLStatementsForReplay().getDDLStatements();
     ArrayList<DDLConflatable> ddlconflatables = new ArrayList<DDLConflatable>();
     for (byte[] ddl : ddls) {
       ddlconflatables.add((DDLConflatable)BlobHelper.deserializeBlob(ddl));
     }
     return ddlconflatables;
   }
   private SerializableRunnable verifyHDFSStoreExistence(final String name) {
     return verifyHDFSStoreExistence(name , false);
   }
    private SerializableRunnable verifyHDFSStoreExistence(final String name, final boolean original) {
      return new SerializableRunnable() {
        @Override
        public void run() {
          HDFSStoreImpl hdfsstore = null;
          assertNotNull(hdfsstore = Misc.getGemFireCache().findHDFSStore(name));
          if (!original) {
            assertEquals (hdfsstore.getMaxFileSize(), 47);
            assertEquals (hdfsstore.getFileRolloverInterval() , 20820);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMaxInputFileCount(), 98);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMaxInputFileSizeMB(), 38);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMaxThreads(), 35);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMinInputFileCount(), 24);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getAutoCompaction(), false);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMajorCompactionIntervalMins(), 372);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMajorCompactionMaxThreads(), 29);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getAutoMajorCompaction(), false);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getOldFilesCleanupIntervalMins(), 2392);
            assertEquals (hdfsstore.getHDFSEventQueueAttributes().getBatchSizeMB(), 23);
            assertEquals (hdfsstore.getHDFSEventQueueAttributes().getBatchTimeInterval(), 2000);
          } else {
            assertEquals (hdfsstore.getMaxFileSize(), 256);
            assertEquals (hdfsstore.getFileRolloverInterval() , 3600);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMaxInputFileCount(), 10);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMaxInputFileSizeMB(), 512);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMaxThreads(), 10);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMinInputFileCount(), 4);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getAutoCompaction(), true);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMajorCompactionIntervalMins(), 720);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getMajorCompactionMaxThreads(), 2);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getAutoMajorCompaction(), true);
            assertEquals (hdfsstore.getHDFSCompactionConfig().getOldFilesCleanupIntervalMins(), 30);
            assertEquals (hdfsstore.getHDFSEventQueueAttributes().getBatchSizeMB(), 32);
            assertEquals (hdfsstore.getHDFSEventQueueAttributes().getBatchTimeInterval(), 60000);
          }
          
          
        }
      };
    }
    protected void setupObservers(VM dataStore) {
        SerializableRunnable setObserver = new SerializableRunnable(
          "Set GemFireXDObserver on DataStore Node") {
        @Override
        public void run() throws CacheException {
          try {
            GemFireXDQueryObserverHolder
                .setInstance(new GemFireXDQueryObserverAdapter() {
                  @Override
                  public void afterQueryParsing(String query, StatementNode qt,
                      LanguageConnectionContext lcc) {
                    throw new GemFireXDRuntimeException("");
                  }
  
                  
                });
          } catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
  
      dataStore.invoke(setObserver);
    }
    
    protected void clearObservers(VM dataStore) {
          SerializableRunnable clearObserver = new SerializableRunnable(
          "Clear GemFireXDObserver on DataStore Node") {
        @Override
        public void run() throws CacheException {
          try {
            GemFireXDQueryObserverHolder.clearInstance();
          } catch (Exception e) {
            throw new CacheException(e) {
            };
          }
        }
      };
  
      dataStore.invoke(clearObserver);
    }
    private SerializableRunnable verifyNoHDFSStoreExistence(final String name) {
      return new SerializableRunnable() {
        @Override
        public void run() {
          assertNull(Misc.getGemFireCache().findHDFSStore(name));
        }
      };
    }
    //Assume no other thread creates the directory at the same time
    private void checkDirExistence(String path) {
     File dir = new File(path);
     if (dir.exists()) {
       delete(dir);
     }
   }
}
