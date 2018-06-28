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

package com.pivotal.gemfirexd.jdbc;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import com.gemstone.gemfire.admin.internal.FinishBackupRequest;
import com.gemstone.gemfire.cache.persistence.PersistentID;
import com.gemstone.gemfire.internal.FileUtil;
import com.gemstone.gemfire.internal.cache.AbstractRegionEntry;
import com.gemstone.gemfire.internal.cache.CacheObserver;
import com.gemstone.gemfire.internal.cache.CacheObserverAdapter;
import com.gemstone.gemfire.internal.cache.CacheObserverHolder;
import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.persistence.BackupManager;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gnu.trove.THashMap;
import com.gemstone.gnu.trove.TIntIntHashMap;
import com.gemstone.gnu.trove.TObjectObjectProcedure;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.access.index.MemIndexScanController;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.access.index.SortedMap2IndexScanController;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.store.access.conglomerate.Conglomerate;

/**
 * @author kneeraj
 * 
 */
public class IndexPersistenceTest extends JdbcTestBase {

  public IndexPersistenceTest(String name) {
    super(name);
  }

  @Override
  protected void tearDown() throws Exception {
    super.tearDown();
    deleteDir(getBackupDirOnly());
    deleteDir(getIncrementalDir());
    deleteDir(getIncremental2Dir());
  }

  @SuppressWarnings("serial")
  private static class IndexAccountObserver extends GemFireXDQueryObserverAdapter {
    private THashMap map;

    public boolean needIndexRecoveryAccounting() {
      return true;
    }

    public void setIndexRecoveryAccountingMap(THashMap map) {
      this.map = map;
    }
    
    public THashMap getMap() {
      return this.map;
    }
  }

  protected String offHeap() {
    return "";  
  }

  protected static File getBackupDir() {
    File dir = getBackupDirOnly();
    dir.mkdirs();
    return dir;
  }


  protected static File getBackupDirOnly() {
    File tmpDir = new File(System.getProperty("java.io.tmpdir"));
    return new File(tmpDir, "backupDir");
  }

  /**
   * @return the incremental backup directory.
   */
  private static File getIncrementalDir() {
    File tmpDir = new File(System.getProperty("java.io.tmpdir"));
    File dir = new File(tmpDir, "incremental");
    if(!dir.exists()) {
      dir.mkdirs();      
    }
    
    return dir;
  }
  /**
   * @return the second incremental backup directory.
   */
  private static File getIncremental2Dir() {
    File tmpDir = new File(System.getProperty("java.io.tmpdir"));
    File dir = new File(tmpDir, "incremental2");
    if(!dir.exists()) {
      dir.mkdirs();      
    }
    
    return dir;    
  }

  private void backup_shutdown_cleanup_restore(File backupDir, File baseDir) throws SQLException, IOException, InterruptedException {
    deleteDirContents(backupDir);
    Map<String, Long> originalFiles = getAllOplogFiles();
    HashSet<PersistentID> status = backup(backupDir, baseDir);
    assertEquals(2, status.size());

    TestUtil.shutDown();

    clearAllOplogFiles();
    restoreBackup(backupDir, 1);
    Map<String, Long> restoredFiles = getAllOplogFiles();
    if(!originalFiles.equals(restoredFiles)) {
      TreeMap<String, Long> missing = new TreeMap<String, Long>(originalFiles);
      TreeMap<String, Long> newFiles = new TreeMap<String, Long>(restoredFiles);
      missing.entrySet().removeAll(restoredFiles.entrySet());
      newFiles.entrySet().removeAll(originalFiles.entrySet());
      fail("Files restored from backup did not match original set. Missing files : " + missing + ", new files " + newFiles);
    }
  }
  
  private HashSet<PersistentID> backup(File backupDir, File baseDir) throws IOException {
    BackupManager backup = Misc.getGemFireCache().startBackup(Misc.getGemFireCache().getDistributedSystem().getDistributedMember());
    backup.prepareBackup();
    HashSet<PersistentID> set = backup.finishBackup(backupDir, baseDir, FinishBackupRequest.DISKSTORE_ALL);
    File incompleteBackup = FileUtil.find(backupDir, ".*INCOMPLETE.*");
    assertNull(incompleteBackup);
    return set;
  }

  protected void restoreBackup(File backupDir, int expectedNumScripts) throws IOException, InterruptedException {
    List<File> restoreScripts = FileUtil.findAll(backupDir, ".*restore.*");
    assertEquals("Restore scripts " + restoreScripts, expectedNumScripts, restoreScripts.size());
    for(File script : restoreScripts) {
      System.out.println("restore scripts:"+script);
      execute(script);
    }
  }

  private void execute(File script) throws IOException, InterruptedException {
    ProcessBuilder pb = new ProcessBuilder(script.getAbsolutePath());
    pb.redirectErrorStream(true);
    Process process = pb.start();

    InputStream is = process.getInputStream();
    byte[] buffer = new byte[1024];
    BufferedReader br = new BufferedReader(new InputStreamReader(is));
    String line;
    while((line = br.readLine()) != null) {
      System.out.println("OUTPUT:" + line);
      //TODO validate output
    };

    assertEquals(0, process.waitFor());
  }
  private static File[] listFiles(File dir) {
    File[] result = dir.listFiles();
    if (result == null) {
      result = new File[0];
    }
    return result;
  }

  public static void printDirectory(String dirname, String testcasename) {
    File[] files = listFiles(new File(dirname));
    if (!testcasename.equals("")) {
      System.out.println("------------------");
    }
    System.out.println(testcasename+":list dir:"+dirname);
    for (File f: files) {
      if (f.getAbsolutePath().contains(".xml") || f.getAbsolutePath().contains(".gfs") 
          || f.getAbsolutePath().contains(".log") || f.getAbsolutePath().contains(".dtd")
          || f.getAbsolutePath().contains(".txt")) {
        continue;
      }
      System.out.println("list dir:"+f+":size="+f.length());
      if (f.isDirectory() && f.exists()) {
        printDirectory(f.getAbsolutePath(), "");
      }
    }
    String dict_dir = dirname+"/datadictionary";
    if (new File(dict_dir).exists()) {
      printDirectory(dict_dir, "");
    }
  }

  private class CrashSimulator extends CacheObserverAdapter {
    public boolean shouldCreateKRFIRF() {
      return false;
    }
  }
  
  public void testRecoveryOfIndexThroughAlterTable_simulateCrash() throws Exception {
    CacheObserver crashSimulator = new CrashSimulator();
    alterTableTest(crashSimulator);
  }

  public void testRecoveryOfIndexThroughAlterTable() throws Exception {
    alterTableTest(null);
  }
  
  public void alterTableTest(CacheObserver crashSimulator) throws Exception {
    String ddl = "create table TMP.T1"
      + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();
    
    for (int j = 0; j < 2; j++) {
      try {
        ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
        GemFireXDQueryObserverHolder.setInstance(observer);
        System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();

        stmt.execute("create schema TMP");
        if (j == 1) {
          ddl += " replicate";
        }
        stmt.execute(ddl);
        PreparedStatement ps = conn.prepareStatement("insert into TMP.T1 values(?, ?, ?)");
        for(int i=0; i<20; i++) {
          ps.setInt(1, i);
          ps.setString(2, "one-c2-"+i);
          ps.setInt(3, i);
          int cnt = ps.executeUpdate();
          assertEquals(1, cnt);
        }
        
        stmt.execute("create table TMP.T2(c12 int not null primary key, "
            + "c22 varchar(20) not null, c32 int not null, c42 int not null, "
            + "c52 int not null) persistent" + (j == 1 ? " replicate" : "") + offHeap());

        stmt.execute("alter table TMP.T2 add constraint t2_fk foreign key (c12) references TMP.T1 (c1)");
        stmt.execute("alter table TMP.T2 add constraint t2_uk1 unique (c32)");
        
        if (crashSimulator != null) {
          LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
        }
        CacheObserverHolder.setInstance(crashSimulator);
        
        ps = conn.prepareStatement("insert into TMP.T2 values(?, ?, ?, ?, ?)");
        for(int i=0; i<5; i++) {
          ps.setInt(1, i);
          ps.setString(2, "c22-"+i);
          ps.setInt(3, i);
          ps.setInt(4, i);
          ps.setInt(5, i);
          int cnt = ps.executeUpdate();
          assertEquals(1, cnt);
        }
        
        // Adding a unique key after some data has been added
        stmt.execute("alter table TMP.T2 add constraint t2_uk2 unique (c52)");
        
        for(int i=5; i<10; i++) {
          ps.setInt(1, i);
          ps.setString(2, "c22-"+i);
          ps.setInt(3, i);
          ps.setInt(4, i);
          ps.setInt(5, i);
          int cnt = ps.executeUpdate();
          assertEquals(1, cnt);
        }

        TestUtil.shutDown();

        LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;

        IndexAccountObserver ob = new IndexAccountObserver();
        GemFireXDQueryObserverHolder.setInstance(ob);
        
        conn = TestUtil.getConnection();

        if (j==0) {
          assertEquals(1, ob.getMap().size());
        }
        else {
          assertEquals(3, ob.getMap().size());
        }
        ob.getMap().forEachEntry(new TObjectObjectProcedure() {
          public boolean execute(Object a, Object b) {
            assertEquals(10, ((Integer)b).intValue());
            return true;
          }
        });
        
        // shut down again and see everything is ok or not
        TestUtil.shutDown();

        LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;

        ob = new IndexAccountObserver();
        GemFireXDQueryObserverHolder.setInstance(ob);
        
        conn = TestUtil.getConnection();

        if (j==0) {
          assertEquals(1, ob.getMap().size());
        }
        else {
          assertEquals(3, ob.getMap().size());
        }
        ob.getMap().forEachEntry(new TObjectObjectProcedure() {
          public boolean execute(Object a, Object b) {
            assertEquals(10, ((Integer)b).intValue());
            return true;
          }
        });
      }
      finally {
        doCleanUp();
      }
    }
  }
  
  public void DISABLED_BUG_51841_testRecoveryOfImplicitIndexes_orig() throws Exception {

	    doTestRecoveryOfImplicitIndexes(false, false);
	    doTestRecoveryOfImplicitIndexes(true, false);    
  }
  
  public void DISABLED_BUG_51841_testRecoveryOfImplicitIndexes() throws Exception {

    doTestRecoveryOfImplicitIndexes(false, true);
    doTestRecoveryOfImplicitIndexes(true, true);
    
  }
  

	public void doTestRecoveryOfImplicitIndexes(boolean replicate,
			boolean singleAmpersand) throws Exception {
    String ddl = "create table TMP.T1"
      + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();
    
    String ddl2 = "create table TMP.T2"
      + "(c12 int not null unique , c22 varchar(20) not null, c32 int not null, foreign key (c32) references TMP.T1(c1)) persistent" + offHeap();

    String origDDL2 = ddl2;

    deleteDir(getBackupDirOnly());
    deleteDir(getIncrementalDir());
    deleteDir(getIncremental2Dir());
    try {
      ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
      GemFireXDQueryObserverHolder.setInstance(observer);
      System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
      Connection conn = TestUtil.getConnection();
      Statement stmt = conn.createStatement();

      stmt.execute("create schema TMP");
      if (replicate) {
        ddl += " replicate";
      }
      stmt.execute(ddl);
      stmt.execute("create index TMP.IDX2 on TMP.T1(c2, c3)");
      stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");
      stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
      stmt.execute("insert into TMP.T1 values(5, 'one', 1), (6, 'two', 2), (7, 'three', 3), (8, 'four', 3)");
      stmt.execute("select * from TMP.T1 where c3 = 3");
      ResultSet rs = stmt.getResultSet();
      int cnt = 0;
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        if ((i == 3) || (i == 4) || (i == 7) || (i == 8)) {
          assertEquals(3, rs.getObject(3));
        }
        cnt++;
      }

      // populate the second table
      if (replicate) {
        origDDL2 += " replicate";
        ddl2 = origDDL2;
      }
      else {
        ddl2 += " partition by column(c12)";
      }
      stmt.execute(ddl2);
      stmt.execute("insert into TMP.T2 values(1, 'one', 2), (2, 'two', 1), (3, 'three', 2), (4, 'four', 8)");

      stmt.execute("create index TMP.IDX22 on TMP.t2(c22)");

      stmt.execute("insert into TMP.T2 values(11, 'thirtyone', 7), (12, 'thirtytwo', 5), (13, 'thirtythree', 3), (14, 'thirtyfour', 3)");

      observer.clear();
      if (singleAmpersand) {
    	  stmt.execute("select * from TMP.T2 where c22 like 'thirty%'");
      } else {
    	  stmt.execute("select * from TMP.T2 where c22 like '%thirty%'");
      }
      
      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        assertTrue((i == 11) || (i == 12) || (i == 13) || (i == 14));
        cnt++;
      }
      observer.addExpectedScanType("TMP.T2", "TMP.IDX22", ScanType.SORTEDMAPINDEX);
      observer.checkAndClear();
      assertEquals(4, cnt);

      GfxdIndexManager iu = (GfxdIndexManager)((LocalRegion)Misc.getGemFireCache().getRegion("/TMP/T2")).getIndexUpdater();

      List<GemFireContainer> list = iu.getIndexContainers();

      for (GemFireContainer index : list) {
        System.out.println("index=" + index + " ID: "
            + index.getQualifiedTableName());
      }

      backup_shutdown_cleanup_restore(getBackupDir(), null);

      IndexAccountObserver ob = new IndexAccountObserver();
      GemFireXDQueryObserverHolder.setInstance(ob);

      conn = TestUtil.getConnection();

      ob.getMap().forEachEntry(new TObjectObjectProcedure() {
        public boolean execute(Object a, Object b) {
          System.out.println("KN: a=" + a + " b=" + b);
          return true;
        }
      });

      System.out.println("KN: ************** First restart ****************");
      assertEquals(5, ob.getMap().size());

      ob.getMap().forEachEntry(new TObjectObjectProcedure() {
        public boolean execute(Object a, Object b) {
          System.out.println("KN: a=" + a + " b=" + b);
          assertEquals(8, ((Integer)b).intValue());
          return true;
        }
      });
      System.out.println("KN: ************** First restart end****************");
      stmt = conn.createStatement();
      GemFireXDQueryObserverHolder.setInstance(observer);

      observer.addExpectedScanType("TMP.T2", "TMP.IDX22", ScanType.SORTEDMAPINDEX);

      if (singleAmpersand) {
    	  stmt.execute("select * from TMP.T2 where c22 like 'thirty%'");
      }
      else {
    	  stmt.execute("select * from TMP.T2 where c22 like '%thirty%'"); 
      }
      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        assertTrue((i == 11) || (i == 12) || (i == 13) || (i == 14));
        cnt++;
      }

      assertEquals(4, cnt);
      observer.checkAndClear();

      // KN: TODO I expect the below scan but the test does table scan
      //observer.addExpectedScanType("TMP.T2", ScanType.SORTEDMAPINDEX);
      stmt.execute("select c32 from TMP.T2");

      rs = stmt.getResultSet();
      cnt = 0;
      TIntIntHashMap res = new TIntIntHashMap();
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        int num = res.get(i.intValue());
        res.put(i.intValue(), ++num);
        cnt++;
      }

      observer.checkAndClear();
      assertEquals(8, cnt);
      assertEquals(2, res.get(2));
      assertEquals(1, res.get(1));
      assertEquals(1, res.get(8));
      assertEquals(1, res.get(7));
      assertEquals(1, res.get(5));
      assertEquals(2, res.get(3));

      observer.clear();
      observer.addExpectedScanType("TMP.T2", "TMP.3__T2__C12", ScanType.SORTEDMAPINDEX);

      stmt.execute("select * from TMP.T2 where c12 = 3 or c12 = 14");

      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        int i = rs.getInt(1);
        assertTrue(i == 3 || i ==14);
        cnt++;
      }

      observer.checkAndClear();
      assertEquals(2, cnt);

      stmt.execute("create index TMP.IDX33 on TMP.T2(c12, c32)");

      observer.clear();
      observer.addExpectedScanType("TMP.T2", "TMP.IDX33", ScanType.SORTEDMAPINDEX);

      stmt.execute("select c12, c22 from TMP.T2 where c12 = 1 and c32 = 2");

      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        int i = rs.getInt(1);
        assertEquals(1, i);
        cnt++;
      }
      observer.checkAndClear();
      assertEquals(1, cnt);

      // Shutdown and recover again should be fine
      backup_shutdown_cleanup_restore(getIncrementalDir(), getBackupDir());
      ob = new IndexAccountObserver();
      GemFireXDQueryObserverHolder.setInstance(ob);

      conn = TestUtil.getConnection();
      stmt = conn.createStatement();

      System.out.println("KN: ************** Printing all at Second restart ****************");
      ob.getMap().forEachEntry(new TObjectObjectProcedure() {
        public boolean execute(Object a, Object b) {
          System.out.println("KN: a=" + a + " b=" + b);
          return true;
        }
      });
      assertEquals(6, ob.getMap().size());

      System.out.println("KN: ************** Second restart ****************");

      ob.getMap().forEachEntry(new TObjectObjectProcedure() {
        public boolean execute(Object a, Object b) {
          System.out.println("KN: a=" + a + " b=" + b);
          assertEquals(8, ((Integer)b).intValue());
          return true;
        }
      });

      System.out.println("KN: ************** Second restart end****************");

      stmt = conn.createStatement();
      GemFireXDQueryObserverHolder.setInstance(observer);

      observer.addExpectedScanType("TMP.T2", "TMP.IDX22", ScanType.SORTEDMAPINDEX);

      if (singleAmpersand) {
    	  stmt.execute("select * from TMP.T2 where c22 like 'thirty%'");
      }
      else {
    	  stmt.execute("select * from TMP.T2 where c22 like '%thirty%'");
      }
      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        assertTrue((i == 11) || (i == 12) || (i == 13) || (i == 14));
        cnt++;
      }
      observer.checkAndClear();
      assertEquals(4, cnt);

      // KN: TODO I expect the below scan but the test does table scan
      //observer.addExpectedScanType("TMP.T2", ScanType.SORTEDMAPINDEX);

      stmt.execute("select c32 from TMP.T2");

      rs = stmt.getResultSet();
      cnt = 0;
      res = new TIntIntHashMap();
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        int num = res.get(i.intValue());
        res.put(i.intValue(), ++num);
        cnt++;
      }

      observer.checkAndClear();
      assertEquals(8, cnt);
      assertEquals(2, res.get(2));
      assertEquals(1, res.get(1));
      assertEquals(1, res.get(8));
      assertEquals(1, res.get(7));
      assertEquals(1, res.get(5));
      assertEquals(2, res.get(3));

      observer.clear();
      observer.addExpectedScanType("TMP.T2", "TMP.3__T2__C12", ScanType.SORTEDMAPINDEX);

      stmt.execute("select * from TMP.T2 where c12 = 3 or c12 = 14");

      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        int i = rs.getInt(1);
        assertTrue(i == 3 || i ==14);
        cnt++;
      }

      // observer.checkAndClear();

      // observer.addExpectedScanType("TMP.T2", ScanType.SORTEDMAPINDEX);
      assertEquals(2, cnt);

      // observer.addExpectedScanType("TMP.T2", ScanType.SORTEDMAPINDEX);

      stmt.execute("select c12, c32 from TMP.T2 where c12 = 1 and c32 = 2");

      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        int i = rs.getInt(1);
        assertEquals(1, i);
        cnt++;
      }
      // observer.checkAndClear();
      assertEquals(1, cnt);

      // Shutdown and recover once more should be fine
      observer.clear();
      backup_shutdown_cleanup_restore(getIncremental2Dir(), getIncrementalDir());
      ob = new IndexAccountObserver();
      GemFireXDQueryObserverHolder.setInstance(ob);

      conn = TestUtil.getConnection();
      stmt = conn.createStatement();

      //        ob.getMap().forEachEntry(new TObjectObjectProcedure() {
      //          public boolean execute(Object a, Object b) {
      //            System.out.println("KN: a=" + a + " b=" + b);
      //            return true;
      //          }
      //        });
      assertEquals(6, ob.getMap().size());

      System.out.println("KN: ************** Third restart ****************");

      ob.getMap().forEachEntry(new TObjectObjectProcedure() {
        public boolean execute(Object a, Object b) {
          System.out.println("KN: a=" + a + " b=" + b);
          // TODO: after restore it is 4, not 8
          // assertEquals(8, ((Integer)b).intValue());
          return true;
        }
      });

      System.out.println("KN: ************** Third restart end****************");

      stmt = conn.createStatement();
      GemFireXDQueryObserverHolder.setInstance(observer);

      observer.addExpectedScanType("TMP.T2", "TMP.IDX22", ScanType.SORTEDMAPINDEX);

      if (singleAmpersand) {
    	  stmt.execute("select * from TMP.T2 where c22 like 'thirty%'");
      }
      else {
    	  stmt.execute("select * from TMP.T2 where c22 like '%thirty%'");
      }
      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        assertTrue((i == 11) || (i == 12) || (i == 13) || (i == 14));
        cnt++;
      }
      observer.checkAndClear();
      assertEquals(4, cnt);

      // KN: TODO I expect the below scan but the test does table scan
      //observer.addExpectedScanType("TMP.T2", ScanType.SORTEDMAPINDEX);

      stmt.execute("select c32 from TMP.T2");

      rs = stmt.getResultSet();
      cnt = 0;
      res = new TIntIntHashMap();
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        int num = res.get(i.intValue());
        res.put(i.intValue(), ++num);
        cnt++;
      }

      observer.checkAndClear();
      assertEquals(8, cnt);
      assertEquals(2, res.get(2));
      assertEquals(1, res.get(1));
      assertEquals(1, res.get(8));
      assertEquals(1, res.get(7));
      assertEquals(1, res.get(5));
      assertEquals(2, res.get(3));
    } finally {
      doCleanUp();
    }
  }
  
  public void DISABLED_BUG_51841_testIncrementalBackupWithIndexCreation() throws Exception {

    String ddl = "create table TMP.T1"
      + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();
    
    deleteDir(getBackupDirOnly());
    deleteDir(getIncrementalDir());
    deleteDir(getIncremental2Dir());
    try {
      System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
      Connection conn = TestUtil.getConnection();
      Statement stmt = conn.createStatement();

      stmt.execute("create schema TMP");
      stmt.execute(ddl);
      stmt.execute("create index TMP.IDX2 on TMP.T1(c2, c3)");
      stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");
      backup_shutdown_cleanup_restore(getBackupDir(), null);
      conn = TestUtil.getConnection();
      
      
      stmt = conn.createStatement();
      stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
      stmt.execute("insert into TMP.T1 values(5, 'one', 1), (6, 'two', 2), (7, 'three', 3), (8, 'four', 3)");
      stmt.execute("select * from TMP.T1 where c3 = 3");
      ResultSet rs = stmt.getResultSet();
      int cnt = 0;
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        if ((i == 3) || (i == 4) || (i == 7) || (i == 8)) {
          assertEquals(3, rs.getObject(3));
        }
        cnt++;
      }
      assertEquals(4, cnt);
      
      backup_shutdown_cleanup_restore(getIncrementalDir(), getBackupDir());
      conn = TestUtil.getConnection();
      stmt = conn.createStatement();
      stmt.execute("drop index TMP.IDX1");
      stmt.execute("create index TMP.IDX1 on TMP.T1(c3, c2)");
      
      backup_shutdown_cleanup_restore(getIncremental2Dir(), getIncrementalDir());
      
      conn = TestUtil.getConnection();
      stmt = conn.createStatement();
      stmt.execute("select * from TMP.T1 where c3 = 3");
      rs = stmt.getResultSet();
      cnt = 0;
      while (rs.next()) {
        Integer i = (Integer)rs.getObject(1);
        if ((i == 3) || (i == 4) || (i == 7) || (i == 8)) {
          assertEquals(3, rs.getObject(3));
        }
        cnt++;
      }
      assertEquals(4, cnt);

      

    } finally {
      doCleanUp();
    }
  }

  public void DISABLED_BUG_51841_testRecoveryOfOnTheFlyIndexCreation() throws Exception {
    
    String ddl = "create table TMP.T1"
        + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();

    for (int j = 0; j < 2; j++) {
      try {
        ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
        GemFireXDQueryObserverHolder.setInstance(observer);
        System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();

        stmt.execute("create schema TMP");
        if (j == 1) {
          ddl += " replicate";
        }
        stmt.execute(ddl);
        stmt.execute("create index TMP.IDX2 on TMP.T1(c2, c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(5, 'one', 1), (6, 'two', 2), (7, 'three', 3), (8, 'four', 3)");
        stmt.execute("select * from TMP.T1 where c3 = 3");
        ResultSet rs = stmt.getResultSet();
        int cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          if ((i == 3) || (i == 4) || (i == 7) || (i == 8)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }
        observer.addExpectedScanType("TMP.T1", "TMP.IDX1", ScanType.SORTEDMAPINDEX);
        observer.addExpectedScanType("TMP.T1", "TMP.T1", ScanType.TABLE);
        observer.checkAndClear();
        assertEquals(4, cnt);
        
        stmt.execute("create index TMP.IDX3 on TMP.T1(c2)");
        
        stmt.execute("create index TMP.IDX4 on TMP.T1(c1, c2, c3)");
        backup_shutdown_cleanup_restore(getBackupDir(), null);
        IndexAccountObserver ob = new IndexAccountObserver();
        GemFireXDQueryObserverHolder.setInstance(ob);
        
        conn = TestUtil.getConnection();
        ob.getMap().forEachEntry(new TObjectObjectProcedure() {
          public boolean execute(Object a, Object b) {
            System.out.println("KN: a=" + a + " b=" + b);
            return true;
          }
        });
        assertEquals(4, ob.getMap().size());

        ob.getMap().forEachEntry(new TObjectObjectProcedure() {
          public boolean execute(Object a, Object b) {
            assertEquals(8, ((Integer)b).intValue());
            return true;
          }
        });
        
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);
        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          if ((i == 3) || (i == 4) || (i == 7) || (i == 8)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }

        observer.addExpectedScanType("TMP.T1", "TMP.IDX1", ScanType.SORTEDMAPINDEX);
        observer.addExpectedScanType("TMP.T1", "TMP.T1", ScanType.TABLE);
        observer.checkAndClear();
        assertEquals(4, cnt);

        stmt.execute("select * from TMP.T1 where c1 = 1 and c2 = 'one' and c3 = 1");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          assertEquals(i, rs.getObject(3));
          cnt++;
        }

        observer.addExpectedScanType("TMP.IDX4", ScanType.SORTEDMAPINDEX);
        assertEquals(1, cnt);
        
        stmt.execute("select * from TMP.T1 where c2 = 'two'");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          String i = rs.getString(2);
          assertTrue(i.equals("two"));
          cnt++;
        }

        observer.addExpectedScanType("TMP.IDX3", ScanType.SORTEDMAPINDEX);
        assertEquals(2, cnt);
        
        // Shutdown and recover again should be fine
        observer.clear();
        stmt.execute("insert into TMP.T1 values(9, 'one', 1), (10, 'two', 2), (11, 'three', 3), (12, 'four', 3)");
        backup_shutdown_cleanup_restore(getBackupDir(), null);
        conn = TestUtil.getConnection();
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);
        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          if ((i == 3) || (i == 4) || (i == 7) || (i == 8) || (i == 11) || (i == 12)) {
            assertEquals(3, rs.getObject(3));
          }
          System.out.println(rs.getObject(1) + ", " + rs.getObject(2) + ", " + rs.getObject(3));
          cnt++;
        }
        observer.addExpectedScanType("TMP.T1", "TMP.IDX1", ScanType.SORTEDMAPINDEX);
        observer.checkAndClear();
        assertEquals(6, cnt);
        
        stmt.execute("select * from TMP.T1 where c2 = 'three'");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          String i = rs.getString(2);
          assertTrue(i.equals("three"));
          cnt++;
        }

        observer.addExpectedScanType("TMP.IDX3", ScanType.SORTEDMAPINDEX);
        assertEquals(3, cnt);

      } finally {
        doCleanUp();
      }
    }
  }
  
  public void testRecoveryAfterNormalShutDown() throws Exception {
    String ddl = "create table TMP.T1"
        + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();
    for (int j = 0; j < 1; j++) {
      try {
        ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
        GemFireXDQueryObserverHolder.setInstance(observer);
        System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();

        stmt.execute("create schema TMP");
        if (j == 1) {
          ddl += " replicate";
        }
        stmt.execute(ddl);
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");

        stmt.execute("select * from TMP.T1 where c3 = 3");
        ResultSet rs = stmt.getResultSet();
        int cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          if ((i == 3) || (i == 4)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        assertEquals(2, cnt);
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          if ((i == 3) || (i == 4)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        assertEquals(2, cnt);
        
        // Shutdown and recover again should be fine
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          if ((i == 3) || (i == 4)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        assertEquals(2, cnt);
        
      } finally {
        doCleanUp();
      }
    }
  }
  
  public void testCompactionWithIdxKrf() throws Exception {
    String ddl = "create table TMP.T1"
        + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();
    for (int j = 0; j < 1; j++) {
      try {
        ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
        GemFireXDQueryObserverHolder.setInstance(observer);
        System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();

        stmt.execute("create schema TMP");
        if (j == 1) {
          ddl += " replicate";
        }
        stmt.execute(ddl);
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        
        PreparedStatement ps = conn.prepareStatement("insert into TMP.T1 values(?, 'word', ?)");
        for(int i =0; i < 50; i++) {
          ps.setInt(1, i);
          ps.setInt(2, i);
          ps.addBatch();
        }
        ps.executeBatch();

        stmt.execute("select * from TMP.T1 where c3 = 3");
        ResultSet rs = stmt.getResultSet();
        int cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          if ((i == 3)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        assertEquals(1, cnt);
        
        GemFireCacheImpl cache = GemFireCacheImpl.getInstance();
        DiskStoreImpl ds = cache.findDiskStore(cache.getDefaultDiskStoreName());
        
        ds.forceRoll();
        //delete enough data to trigger a compaction
        stmt.execute("delete from TMP.T1 where c1 > 5");
        ds.forceCompaction();
        
        //Need a better way to force a roll of the oplogs but this will do it.
        TestUtil.shutDown();
        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        stmt = conn.createStatement();
        
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          Integer i = (Integer)rs.getObject(1);
          if ((i == 3)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        assertEquals(1, cnt);
      } finally {
        doCleanUp();
      }
    }
  }
  
  public void testRecoveryAfterNormalShutDown_withUpdatesAlso_noPK() throws Exception {
    String ddl = "create table TMP.T1"
        + "(c1 int not null, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();
    for (int j = 0; j < 2; j++) {
      try {
        ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
        GemFireXDQueryObserverHolder.setInstance(observer);
        System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();

        stmt.execute("create schema TMP");
        if (j == 1) {
          ddl += " replicate";
        }
        stmt.execute(ddl);
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");

        Statement stmt2 = conn.createStatement();
        stmt2.execute("update TMP.T1 set c3 = 4 where c3 = 3");
        stmt2.execute("select * from TMP.T1 where c3 = 3");
        ResultSet rs = stmt2.getResultSet();
        assertFalse(rs.next());

        stmt = conn.createStatement();
        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        assertFalse(rs.next());

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        assertFalse(rs.next());

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        
        // recover again should be alright
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        assertFalse(rs.next());

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
      } finally {
        doCleanUp();
      }
    }
  }
  
  public void testRecoveryAfterNormalShutDown_withUpdatesAlso() throws Exception {
    String ddl = "create table TMP.T1"
        + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();
    for (int j = 0; j < 2; j++) {
      try {
        ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
        GemFireXDQueryObserverHolder.setInstance(observer);
        System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();

        stmt.execute("create schema TMP");
        if (j == 1) {
          ddl += " replicate";
        }
        stmt.execute(ddl);
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");

        Statement stmt2 = conn.createStatement();
        stmt2.execute("update TMP.T1 set c3 = 4 where c3 = 3");
        stmt2.execute("select * from TMP.T1 where c3 = 3");
        ResultSet rs = stmt2.getResultSet();
        assertFalse(rs.next());

        stmt = conn.createStatement();
        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        assertFalse(rs.next());

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        assertFalse(rs.next());

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        
        // recover again should be alright
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        assertFalse(rs.next());

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
      } finally {
        doCleanUp();
      }
    }
  }
  
  
  public void testNoMemoryLeakOnCrashIndexRecovery() throws Exception {
    String ddl = "create table TMP.T1"
        + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) "
        + "eviction by lrucount 1 evictaction overflow synchronous replicate persistent"
        + offHeap();
    for (int j = 0; j < 1; j++) {
      try {
        System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();
        CrashSimulator crashSimulator = new CrashSimulator();

        if (crashSimulator != null) {
          LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
        }
        CacheObserverHolder.setInstance(crashSimulator);
        stmt.execute("create schema TMP");
        if (j == 1) {
          ddl += " replicate";
        }
        stmt.execute(ddl);
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");

        stmt.execute("select * from TMP.T1 where c3 = 3");
        ResultSet rs = stmt.getResultSet();
        int cnt = 0;
        while (rs.next()) {
          Integer i = (Integer) rs.getObject(1);
          if ((i == 3) || (i == 4)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        final List<CompactCompositeIndexKey> cciks = new ArrayList<CompactCompositeIndexKey>();
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder
            .setInstance(new GemFireXDQueryObserverAdapter() {
              @Override
              public void scanControllerOpened(Object sc, Conglomerate conglom) {
                try {
                  // ignore calls from management layer
                  if (sc instanceof SortedMap2IndexScanController) {
                    SortedMap2IndexScanController indexScan = (SortedMap2IndexScanController) sc;
                    Class<?> memIndexSCClass = MemIndexScanController.class;
                    Field openConglomField = memIndexSCClass
                        .getDeclaredField("openConglom");
                    openConglomField.setAccessible(true);
                    OpenMemIndex mi = (OpenMemIndex) openConglomField.get(sc);
                    ConcurrentSkipListMap<Object, Object> skipListMap = mi
                        .getGemFireContainer().getSkipListMap();

                    for (Object key : skipListMap.keySet()) {
                      if (key instanceof CompactCompositeIndexKey) {
                        cciks.add((CompactCompositeIndexKey) key);
                      }
                    }

                  }
                } catch (Exception e) {
                  throw new RuntimeException(e);
                }
              }
            });

        // Collect all the byte[] present in region entries
        LocalRegion region = Misc.getRegionByPath("/TMP/T1");
        assertNotNull(region);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        cnt = 0;
        while (rs.next()) {
          Integer i = (Integer) rs.getObject(1);
          if ((i == 3) || (i == 4)) {
            assertEquals(3, rs.getObject(3));
          }
          cnt++;
        }
        HashSet<Object> bytesStoredInRegion = new HashSet<Object>();
        Set<Object> keys = region.keys();
        for (Object key : keys) {
          AbstractRegionEntry are = (AbstractRegionEntry) region
              .basicGetEntry(key);
          Object val = are.getValueInVM(region);
          bytesStoredInRegion.add(val);
        }
        assertFalse(cciks.isEmpty());
        for (CompactCompositeIndexKey ccik : cciks) {
          Object valBytes = ccik.getValueByteSource();
          if (valBytes != null) {
            assertTrue(bytesStoredInRegion.contains(valBytes));
          }
          ccik.releaseValueByteSource(valBytes);
        }
        for(Object valBytes: bytesStoredInRegion) {
          if(valBytes instanceof OffHeapByteSource) {
            ((OffHeapByteSource)valBytes).release();
          }
        }

        // Shutdown and recover again should be fine
        TestUtil.shutDown();

      } finally {
        doCleanUp();
      }
    }
  }
  
  public void testRecoveryAfterNormalShutDown_withUpdatesAlso_txn()
      throws Exception {
    String ddl = "create table TMP.T1"
        + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null) persistent" + offHeap();
    for (int j = 0; j < 4; j++) {
      try {
        ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
        GemFireXDQueryObserverHolder.setInstance(observer);
        System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "true");
        Connection conn = TestUtil.getConnection();
        Statement stmt = conn.createStatement();

        stmt.execute("create schema TMP");
        if (j > 1) {
          ddl += " replicate";
        }
        stmt.execute(ddl);
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");

        conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
        conn.setAutoCommit(false);  
        
        Statement stmt2 = conn.createStatement();
        stmt2.execute("update TMP.T1 set c3 = 4 where c3 = 3");
        stmt2.execute("select * from TMP.T1 where c3 = 3");
        ResultSet rs = stmt2.getResultSet();
        assertFalse(rs.next());
        if (j % 2 == 0) {
          conn.commit();
        }
        else {
          conn.rollback();
        }

        stmt = conn.createStatement();
        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        if (j % 2 == 0) {
          assertFalse(rs.next());
        }
        else {
          assertTrue(rs.next());
          int cnt = 1;
          while (rs.next()) {
            cnt++;
          }
          assertEquals(2, cnt);
        }

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        if (j % 2 == 0) {
          assertFalse(rs.next());
        }
        else {
          assertTrue(rs.next());
          int cnt = 1;
          while (rs.next()) {
            cnt++;
          }
          assertEquals(2, cnt);
        }

        observer.addExpectedScanType("TMP.IDX1", ScanType.SORTEDMAPINDEX);
        // observer.checkAndClear();
        // recover again should be alright
        TestUtil.shutDown();

        conn = TestUtil.getConnection();
        checkIndexRecovery(true);
        stmt = conn.createStatement();
        GemFireXDQueryObserverHolder.setInstance(observer);

        stmt.execute("select * from TMP.T1 where c3 = 3");
        rs = stmt.getResultSet();
        if (j % 2 == 0) {
          assertFalse(rs.next());
        }
        else {
          assertTrue(rs.next());
          int cnt = 1;
          while (rs.next()) {
            cnt++;
          }
          assertEquals(2, cnt);
        }
      } finally {
        doCleanUp();
      }
    }
  }
 
  private void doCleanUp() throws SQLException {
    System.setProperty(GfxdConstants.GFXD_PERSIST_INDEXES, "false");
    TestUtil.shutDown();
    clearAllOplogFiles();
    CacheObserverHolder.setInstance(null);
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;
  }

  private void checkIndexRecovery(boolean expected) {
    boolean actual = GemFireStore.getBootingInstance().didIndexRecovery();
    assertEquals(expected, actual);
  }
}
