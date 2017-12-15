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
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;

import com.gemstone.gemfire.internal.cache.DiskStoreImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.Oplog;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.concurrent.ConcurrentTHashSet;
import com.pivotal.gemfirexd.DistributedSQLTestBase;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserver;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.GfxdConstants;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.index.GfxdIndexManager;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.GemFireStore;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;

import io.snappydata.test.dunit.SerializableRunnable;
import io.snappydata.test.dunit.VM;
import io.snappydata.test.util.TestException;

/**
 * @author kneeraj
 * 
 */
@SuppressWarnings("serial")
public class IndexPersistenceDUnit extends DistributedSQLTestBase {

  public IndexPersistenceDUnit(String name) {
    super(name);
  }

  @Override
  protected void vmTearDown() throws Exception {
    super.vmTearDown();
    GemFireStore.setTestNewIndexFlag(false);
  }

  public static void setSystemProperty(String key, String value) {
    //Misc.getCacheLogWriter().info("setting system propert: " + key + " to " + value);
    getGlobalLogger().info("setting system property: " + key + " to " + value);
    System.setProperty(key, value);
    if (value != null && value.equals("false")) {
      System.clearProperty(key);
    }
  }

  public static void setTestNewIndexFlag() {
    GemFireStore.setTestNewIndexFlag(true);
  }

  public static void setTestIndexRecreateFlag() {
    GemFireXDQueryObserver observer = new GemFireXDQueryObserverAdapter() {
      public boolean testIndexRecreate() {
        return true;
      }
    };
    GemFireXDQueryObserverHolder.setInstance(observer);
  }

  public static void checkIndexRecovery(boolean expected) {
    boolean actual = GemFireStore.getBootedInstance().didIndexRecovery();
    assertEquals(expected, actual);
  }

  public static void checkProperIndex(String tNamePath, String idxName,
      int indexSize) {
    getGlobalLogger().info("checkProperIndex called with tNamePath: " + tNamePath + ", index name: " + idxName);
    GfxdIndexManager im = (GfxdIndexManager)((LocalRegion)Misc.getRegion(tNamePath, true, false)).getIndexUpdater();
    ConcurrentSkipListMap<Object, Object> indexMap = null;
    List<GemFireContainer> indexContainers = im.getIndexContainers();
    for(GemFireContainer c : indexContainers) {
      getGlobalLogger().info("indexcontainer name: " + c.getName() + ", qualified table name: " + c.getQualifiedTableName());
      if (c.getName().toString().contains(idxName)) {
        getGlobalLogger().info("indexcontainer name: " + c.getName() + " matched with index name: " + idxName);
        Misc.getCacheLogWriter().info("indexcontainer name: " + c.getName() + " matched with index name: " + idxName);
        indexMap = c.getSkipListMap();
        break;
      }
      else {
        getGlobalLogger().info("indexcontainer name: " + c.getName() + " did not match with index name: " + idxName);
      }
    }
    assertNotNull(indexMap);
    final Set<Map.Entry<Object, Object>> s = indexMap.entrySet();
    int totCnt = 0;
    for (Map.Entry<Object, Object> entry : s) {
      Object val = entry.getValue();
      if (val instanceof RowLocation) {
        totCnt++;
        continue;
      }
      else if (val instanceof RowLocation[]) {
        totCnt += ((RowLocation[])val).length;
        continue;
      }
      else if (val instanceof ConcurrentTHashSet<?>) {
        totCnt += ((ConcurrentTHashSet<?>)val).size();
      }
    }
    assertEquals(indexSize, totCnt);
  }

  public static void forceCompaction() {
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache == null) {
      return;
    }
    Collection<DiskStoreImpl> dsImpls = cache.listDiskStores();
    assert dsImpls != null && dsImpls.size() > 0;
    getGlobalLogger().info("Disk Stores are: " + dsImpls);
    boolean compacted = false;
    for (DiskStoreImpl dsi : dsImpls) {
      if (dsi.getName().equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)) {
        continue;
      }
      compacted = dsi.forceCompaction();
      getGlobalLogger().info(
          "compaction happened=" + compacted
              + " in some oplogs in disk store: " + dsi.getName());
    }
  }
  
  public static void forceRolling() {
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache == null) {
      return;
    }
    Collection<DiskStoreImpl> dsImpls = cache.listDiskStores();
    assert dsImpls != null && dsImpls.size() > 0;
    getGlobalLogger().info("Disk Stores are: " + dsImpls);
    for (DiskStoreImpl dsi : dsImpls) {
      if (dsi.getName().equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)) {
        continue;
      }
      dsi.forceRoll(true);
      getGlobalLogger().info(
          "force roll called on disk store: " + dsi.getName());
    }
  }

  public static void setTestOplogToTestForCompaction() {
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache == null) {
      return;
    }
    Collection<DiskStoreImpl> dsImpls = cache.listDiskStores();
    assert dsImpls != null && dsImpls.size() > 0;
    getGlobalLogger().info("Disk Stores are: " + dsImpls);
    for (DiskStoreImpl dsi : dsImpls) {
      if (dsi.getName().equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)) {
        continue;
      }
      Oplog currOplog = dsi.getPersistentOplogSet(null).getChild();
      dsi.TEST_oplogCompact(currOplog);
      getGlobalLogger().info(
          "force roll called on disk store: " + dsi.getName());
    }
  }
  
  public static void unsetTestOplogToTestForCompaction() {
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache == null) {
      return;
    }
    Collection<DiskStoreImpl> dsImpls = cache.listDiskStores();
    assert dsImpls != null && dsImpls.size() > 0;
    getGlobalLogger().info("Disk Stores are: " + dsImpls);
    for (DiskStoreImpl dsi : dsImpls) {
      if (dsi.getName().equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)) {
        continue;
      }
      dsi.TEST_oplogCompact(null);
      getGlobalLogger().info(
          "force roll called on disk store: " + dsi.getName());
    }
  }
  
  public static Boolean testOplogCompacted() {
    GemFireCacheImpl cache = Misc.getGemFireCacheNoThrow();
    if (cache == null) {
      return Boolean.TRUE;
    }
    Collection<DiskStoreImpl> dsImpls = cache.listDiskStores();
    assert dsImpls != null && dsImpls.size() > 0;
    getGlobalLogger().info("testOplogCompacted::Disk Stores are: " + dsImpls);
    for (DiskStoreImpl dsi : dsImpls) {
      if (dsi.getName().equals(GfxdConstants.GFXD_DD_DISKSTORE_NAME)) {
        continue;
      }
      getGlobalLogger().info(
          "testOplogCompacted::is test oplog compacted called on: "
              + dsi.getName());
      return dsi.isTestOplogCompacted();
    }
    return false;
  }

  public static void runConstraintTest() throws Exception {
    String ddl = "create table TMP.T1"
      + "(c1 int not null primary key, c2 varchar(20) not null unique, c3 int not null unique) replicate persistent";
    
    String ddl_dependent = "create table TMP.T2"
        + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null, "
        + "constraint pk_fk foreign key (c1) references TMP.T1 (c1), constraint uk_fk foreign key (c3) references TMP.T1 (c3)) replicate persistent";
    String ddlToExec = ddl;
    String ddl2ToExec = ddl_dependent;

    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("create schema TMP");
    stmt.execute(ddlToExec);
    stmt.execute(ddl2ToExec);
    
    PreparedStatement ps1 = conn.prepareStatement("insert into TMP.T1 values (?, ?, ?)");
    for(int i=0;i <10; i++) {
      ps1.setInt(1, i);
      ps1.setString(2, "str-"+i);
      ps1.setInt(3, i*10);
    }
    
    ps1 = conn.prepareStatement("insert into TMP.T2 values (?, ?, ?)");
    for(int i=0;i <10; i++) {
      ps1.setInt(1, i);
      ps1.setString(2, "str-"+i);
      ps1.setInt(3, i*10);
    }
  }

  public static void addAlterTableForC2() throws Exception {
    Connection conn = TestUtil.getConnection();
    Statement stmt = conn.createStatement();
    stmt.execute("alter table TMP.T2 "
        + "add constraint cust_fk foreign key (c2) references " + "TMP.T1 (c2)");
  }
  
  public void testConstraintIndexes() throws Exception {
    Statement stmt = null;
    boolean success = false;
    try {
      //System.setProperty(Attribute.GFXD_PERSIST_INDEXES, "true");
      deleteAllOplogFiles();
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, "true" });
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.TRACE_PERSIST_INDEX, "true" });
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.TRACE_PERSIST_INDEX_FINEST, "true" });

      startVMs(1, 3);

      // for (int j = 0; j < 1; j++) {

      VM vm3 = serverVMs.get(1);
      vm3.invoke(IndexPersistenceDUnit.class, "runConstraintTest");

      stopVMNum(-3);
      VM vm4 = serverVMs.get(2);
      vm4.invoke(IndexPersistenceDUnit.class, "setTestNewIndexFlag");

      restartServerVMNums(new int[] { 3 }, 0, null, null);

      vm4.invoke(IndexPersistenceDUnit.class, "setTestNewIndexFlag",
          new Object[] { Boolean.FALSE, "/TMP/T2" });

      stmt = TestUtil.getConnection().createStatement();

      stopVMNum(-3);

      vm3.invoke(IndexPersistenceDUnit.class, "addAlterTableForC2");

      vm4 = serverVMs.get(2);
      vm4.invoke(IndexPersistenceDUnit.class, "setTestNewIndexFlag");

      restartServerVMNums(new int[] { 3 }, 0, null, null);

      vm4.invoke(IndexPersistenceDUnit.class, "setTestNewIndexFlag",
          new Object[] { Boolean.TRUE, "/TMP/T2" });

      stmt.execute("drop table TMP.T2");
      stmt.execute("drop table TMP.T1");
      success = true;
      deleteAllOplogFiles();
      // }
    } finally {
      if (success) {
        stmt.execute("drop schema TMP RESTRICT");
      }
      deleteAllOplogFiles();
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, "false" });
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.TRACE_PERSIST_INDEX, "false" });
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.TRACE_PERSIST_INDEX_FINEST, "false" });
    }
  }

  public static void setTestNewIndexFlag(Boolean expected, String regionPath) {
    LocalRegion r = (LocalRegion)Misc.getRegion(regionPath, false, false);
    GfxdIndexManager sqlim = (GfxdIndexManager)r.getIndexUpdater();
    getGlobalLogger().info(
        "setTestNewIndexFlag called with args " + expected + " and "
            + regionPath + " and indexupdater = " + sqlim + " test flag: "
            + DiskStoreImpl.TEST_NEW_CONTAINER);
    List<GemFireContainer> list = sqlim.getAllIndexes();
    getGlobalLogger().info(
        "list of index containers are: " + Arrays.toString(list.toArray()));
    if (expected) {
      getGlobalLogger().info(
          "new index list is: " + r.getDiskStore().TEST_NEW_CONTAINER_LIST);
      assertNotNull(r.getDiskStore().TEST_NEW_CONTAINER_LIST);
    }
    else {
      assertNull(r.getDiskStore().TEST_NEW_CONTAINER_LIST);
    }
  }

  public void testNewIndexCreatedElseWhereWhenAMemberDown() throws Exception {
    Statement stmt = null;
    boolean success = false;
    try {
      deleteAllOplogFiles();
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, "true" });
      String ddl = "create table TMP.T1"
          + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null)";
      File file1 = new File(IndexPersistenceDUnit.getClassName());
      file1.mkdirs();
      
      startVMs(1, 3);
      Connection conn = TestUtil.getConnection();
      stmt = conn.createStatement();
      stmt.execute("create schema TMP");

      stmt.execute("create diskstore teststore ('" + file1.getPath() +"')" );
      for (int j = 0; j < 2; j++) {
        String ddlToExec = ddl;
        if (j == 1) {
          ddlToExec += " replicate persistent 'teststore'";
        }
        else {
          ddlToExec += " partition by list(c1) (values(1, 2, 3, 4, 5, 6, 7, 8)) redundancy 2 persistent 'teststore'";
        }
        stmt.execute(ddlToExec);
        stmt.execute("create index TMP.IDX2 on TMP.T1(c2, c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(5, 'one', 1), (6, 'two', 2), (7, 'three', 3), (8, 'four', 3)");
        stmt.execute("select * from TMP.T1 where c3 = 3");
        stopVMNum(-3);
        stmt.execute("create index TMP.NEW_INDEX on TMP.T1(c2)");
        restartServerVMNums(new int[] { 3 }, 0, null, null);
        VM vm4 = serverVMs.get(2);
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
          new Object[] {"/TMP/T1", "NEW_INDEX", Integer.valueOf(8)});
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] { "/TMP/T1", "IDX2" + "", Integer.valueOf(8) });
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] { "/TMP/T1", "IDX1", Integer.valueOf(8) });
        vm4.invoke(IndexPersistenceDUnit.class, "checkIndexRecovery", new Object[] {true});
        // stopping and restarting again as the newest index created will not
        // be a new index for this vm any more and the index structures should 
        // get populated properly. 
        stopVMNum(-3);
        restartServerVMNums(new int[] { 3 }, 0, null, null);
        vm4 = serverVMs.get(2);
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
          new Object[] {"/TMP/T1", "NEW_INDEX", Integer.valueOf(8)});
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] { "/TMP/T1", "IDX2" + "", Integer.valueOf(8) });
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] { "/TMP/T1", "IDX1", Integer.valueOf(8) });
        vm4.invoke(IndexPersistenceDUnit.class, "checkIndexRecovery", new Object[] {true});
        stmt.execute("drop table TMP.T1");
        success = true;
        deleteAllOplogFiles();
      }
    } catch (RuntimeException t) {
      getLogWriter().info("got run time exception", t);
      fail("no exception expected", t);
    } catch (Throwable t) {
      getLogWriter().info("got exception", t);
      fail("no exception expected", t);
    } finally {
      if (success) {
        stmt.execute("drop schema TMP RESTRICT");
      }
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, "false" });
    }
  }

  public void testIndexRe_Creation() throws Exception {
    Statement stmt = null;
    boolean success = false;
    try {
      deleteAllOplogFiles();
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, "true" });
      String ddl = "create table TMP.T1"
          + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null)";
      File file1 = new File(IndexPersistenceDUnit.getClassName());
      file1.mkdirs();

      startVMs(1, 3);
      Connection conn = TestUtil.getConnection();
      stmt = conn.createStatement();
      stmt.execute("create schema TMP");

      stmt.execute("create diskstore teststore_two ('" + file1.getPath() +"')" );
      for (int j = 0; j < 2; j++) {
        String ddlToExec = ddl;
        if (j == 1) {
          ddlToExec += " replicate persistent 'teststore_two'";
        }
        else {
          ddlToExec += " partition by list(c1) (values(1, 2, 3, 4, 5, 6, 7, 8)) redundancy 2 persistent 'teststore_two'";
        }
        stmt.execute(ddlToExec);
        stmt.execute("create index TMP.IDX2 on TMP.T1(c2, c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(5, 'one', 1), (6, 'two', 2), (7, 'three', 3), (8, 'four', 3)");
        stmt.execute("select * from TMP.T1 where c3 = 3");
        stopVMNum(-3);
        stmt.execute("create index TMP.NEW_INDEX on TMP.T1(c2)");
        VM vm4 = serverVMs.get(2);
        vm4.invoke(IndexPersistenceDUnit.class, "setTestIndexRecreateFlag");
        restartServerVMNums(new int[]{3}, 0, null, null);
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] {"/TMP/T1", "NEW_INDEX", Integer.valueOf(8)});
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] { "/TMP/T1", "IDX2" + "", Integer.valueOf(8) });
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] { "/TMP/T1", "IDX1", Integer.valueOf(8) });
        vm4.invoke(IndexPersistenceDUnit.class, "checkIndexRecovery", new Object[] {true});
        stmt.execute("drop table TMP.T1");
        success = true;
        deleteAllOplogFiles();
      }
    } catch (RuntimeException t) {
      getLogWriter().info("got run time exception", t);
      fail("no exception expected", t);
    } catch (Throwable t) {
      getLogWriter().info("got exception", t);
      fail("no exception expected", t);
    } finally {
      if (success) {
        stmt.execute("drop schema TMP RESTRICT");
      }
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, "false" });
    }
  }

  public void _testCompaction() throws Exception {
    try {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, "true" });
      String ddl = "create table TMP.T1"
          + "(c1 int not null primary key, c2 varchar(20) not null, c3 int not null)";
      startVMs(1, 3);
      Connection conn = TestUtil.getConnection();
      Statement stmt = conn.createStatement();
      stmt.execute("create schema TMP");
      for (int j = 0; j < 2; j++) {
        String ddlToExec = ddl;
        if (j == 1) {
          ddlToExec += " replicate persistent";
        }
        else {
          ddlToExec += " partition by list(c1) (values(1, 2, 3, 4, 5, 6, 7, 8)) redundancy 2 persistent";
        }
        stmt.execute(ddlToExec);
        stmt.execute("create index TMP.IDX2 on TMP.T1(c2, c3)");
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");
        stmt.execute("create index TMP.IDX1 on TMP.T1(c3)");
        stmt.execute("insert into TMP.T1 values(5, 'one', 1), (6, 'two', 2), (7, 'three', 3), (8, 'four', 3)");
        stmt.execute("select * from TMP.T1 where c3 = 3");
        invokeInEveryVM(IndexPersistenceDUnit.class, "setTestOplogToTestForCompaction");
        
        stmt.execute("delete from TMP.T1");
        // force rolling
        invokeInEveryVM(IndexPersistenceDUnit.class, "forceRolling");
        // force compaction in all vms
        invokeInEveryVM(IndexPersistenceDUnit.class, "forceCompaction");
        
        // wait for the current oplog to be compacted
        waitForCriterion(new WaitCriterion() {
          @Override
          public boolean done() {
            while (true) {
              VM serverVM = getServerVM(1);
              if (!Boolean.TRUE.equals(serverVM.invoke(
                  IndexPersistenceDUnit.class, "testOplogCompacted"))) {
                continue;
              }
              serverVM = getServerVM(2);
              if (!Boolean.TRUE.equals(serverVM.invoke(
                  IndexPersistenceDUnit.class, "testOplogCompacted"))) {
                continue;
              }
              serverVM = getServerVM(3);
              if (!Boolean.TRUE.equals(serverVM.invoke(
                  IndexPersistenceDUnit.class, "testOplogCompacted"))) {
                continue;
              }
              return Boolean.TRUE;
            }
          }

          @Override
          public String description() {
            return "waiting for GII to start";
          }
        }, 30000, 500, true);
        
        stmt.execute("insert into TMP.T1 values(1, 'one', 1), (2, 'two', 2), (3, 'three', 3), (4, 'four', 3)");
        stmt.execute("insert into TMP.T1 values(5, 'one', 1), (6, 'two', 2), (7, 'three', 3), (8, 'four', 3)");
        
        stopVMNum(-3);
        stmt.execute("create index TMP.NEW_INDEX on TMP.T1(c2)");
        restartServerVMNums(new int[] { 3 }, 0, null, null);
        VM vm4 = serverVMs.get(2);
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] {"/TMP/T1", "NEW_INDEX", Integer.valueOf(8)});
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] { "/TMP/T1", "IDX2" + "", Integer.valueOf(8) });
        vm4.invoke(IndexPersistenceDUnit.class, "checkProperIndex",
            new Object[] { "/TMP/T1", "IDX1", Integer.valueOf(8) });
        stmt.execute("drop table TMP.T1");
      }
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, "false" });
      invokeInEveryVM(IndexPersistenceDUnit.class, "unsetTestOplogToTestForCompaction");
    }
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

  private void verifyRowsIndexScanSNAP1933(Connection conn, String[] tableNames,
      String[] indexNames, int maxValInsert, String alias) throws Exception {
    final int delta1 = 100;
    int sz = tableNames.length;
    Statement s = conn.createStatement();
    for (int i =0; i < sz; i++) {
      ResultSet rs = s.executeQuery("select count(*) "
          + alias
          + " from ODS."
          + tableNames[i]
          + " --gemfirexd-properties index="
          + indexNames[i]
          + "");
      while(rs.next()) {
        long l = rs.getLong(1);
        assertEquals(maxValInsert, l);
      }
    }
  }

  private void verifyRowsTableScanSNAP1933(Connection conn, String[] tableNames,
      int maxValInsert) throws Exception {
    final int delta1 = 100;
    int sz = tableNames.length;
    Statement s = conn.createStatement();
    for (int i =0; i < sz; i++) {
      ResultSet rs = s.executeQuery("select count(*) "
          + " from ODS."
          + tableNames[i]
          + " --gemfirexd-properties index=null"
          + "");
      while(rs.next()) {
        long l = rs.getLong(1);
        assertEquals(maxValInsert, l);
      }
    }
  }

  private void updateRowsSNAP1933(Connection conn, String[] tableNames,
      int delta) throws Exception {
    final int delta1 = 100;
    int sz = tableNames.length;
    Statement s = conn.createStatement();
    for (int i =0; i < sz; i++) {
      for (int j = 0; j < 10; j++) {
        int id = delta1 * j + 31 + delta;
        int r = s.executeUpdate("update ODS."
            + tableNames[i]
            + " set cd_typ_ref_id=" + id + 10 + " where CD_VAL_ID=" +  id);
        assert(r == 1);
      }
    }
  }

  private int restartServerSNAP1933(String[] tableNames, String[] indexNames, int maxValInsert,
      int netPort, int restartServerNum, Properties serverInfo, int delta, String alias,
      String persistIndexes) throws Exception {
    stopVMNums(-restartServerNum);
    Connection conn1 = TestUtil.getNetConnection(netPort, null, null);
    updateRowsSNAP1933(conn1, tableNames, delta);
    restartServerVMNums(new int[] { restartServerNum }, 0, null, serverInfo);
    invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
        new Object[] { GfxdConstants.GFXD_PERSIST_INDEXES, persistIndexes });

    // Start network server on the VMs
    final int netPort2 = startNetworkServer(restartServerNum, null, null);
    Connection conn2_1 = TestUtil.getNetConnection(netPort2, null, null);
    verifyRowsTableScanSNAP1933(conn2_1, tableNames, maxValInsert);
    Connection conn2_2 = TestUtil.getNetConnection(netPort2, null, null);
    verifyRowsIndexScanSNAP1933(conn2_2, tableNames, indexNames, maxValInsert, alias);

    // ensure recovery is complete before restarting again
    serverSQLExecute(1, "call sys.rebalance_all_buckets()");

    return netPort2;
  }

  private void createTableSNAP1933(int redundancy, int buckets, boolean withEviction,
      final String tableName, final String indexName,
      final int maxValInsert) throws Exception {
    String persistExtension = " PERSISTENT ASYNCHRONOUS";
    if (withEviction) {
      persistExtension = " EVICTION BY LRUCOUNT 1 EVICTACTION OVERFLOW PERSISTENT ASYNCHRONOUS";
    }
    serverSQLExecute(1, "Create table "
        + " ODS." + tableName
        + "(cd_val_id bigint GENERATED BY DEFAULT AS IDENTITY  NOT NULL,"
        + " ver bigint NOT NULL,"
        + " client_id bigint NOT NULL,"
        + " cd_val varchar(50) NOT NULL,"
        + " cd_typ_ref_id bigint NOT NULL,"
        + " src_sys_ref_id varchar(10) NOT NULL,"
        + " src_sys_rec_id varchar(150),"
        + " vld_frm_dt TIMESTAMP NOT NULL,"
        + " vld_to_dt TIMESTAMP,"
        + " PRIMARY KEY (client_id,cd_val_id)"
        + " ) PARTITION BY COLUMN (cd_val_id)"
        + " REDUNDANCY " + redundancy
        + " BUCKETS " + buckets
        + persistExtension);

    serverSQLExecute(1, "create index ODS." + indexName
        + " on ODS." + tableName
        + " (cd_typ_ref_id)");

    serverExecute(1, new SerializableRunnable() {
      @Override
      public void run() {
        try {
          Connection conn = TestUtil.getConnection();
          PreparedStatement pstmt = conn.prepareStatement("insert into ODS."
              + tableName + " values(?,?,?,'nCZTBAdWBJslcVrJhumxuE',?,"
              + "'fsHxXsyktJ','tENSZGopJOKFTNzKLnWPlAMnjNPJKXBfWhbFFK',"
              + "'2012-01-18 15:13:43','2012-01-18 13:08:07')");
          for (int i = 1; i < maxValInsert + 1; i++) {
            int intVal = i;
            pstmt.setInt(1, intVal);
            pstmt.setInt(2, 1997 + intVal);
            pstmt.setInt(3, intVal);
            pstmt.setInt(4, 328647 + intVal);
            pstmt.addBatch();
          }
          pstmt.executeBatch();
        } catch (java.sql.SQLException sqle) {
          throw new TestException(sqle.toString(), sqle);
        }
      }
    });

    sqlExecuteVerify(new int[]{}, new int[]{1, 2}, "select count(*) from ODS."
        + tableName, null, "" + maxValInsert);
  }

  private void verifyAtLastSNAP1933(int maxValInsert, String[] tableNames,
      String[] indexNames) throws Exception {
    int sz = tableNames.length;
    for (int i =0; i < sz; i++) {
      sqlExecuteVerify(new int[]{}, new int[]{1, 2, 3}, "select count(*) from ODS."
          + tableNames[i]
          + " --gemfirexd-properties index="
          + indexNames[i]
          + "", null, "" + maxValInsert);
    }
  }

  private void dropIndexesSNAP1933(String[] indexNames) throws Exception {
    int sz = indexNames.length;
    for (int i =0; i < sz; i++) {
      serverSQLExecute(1, "Drop index " + " ODS." + indexNames[i]);
    }
  }

  private void dropTablesSNAP1933(String[] tableNames) throws Exception {
    int sz = tableNames.length;
    for (int i =0; i < sz; i++) {
      serverSQLExecute(1, "Drop table " + " ODS." + tableNames[i]);
    }
  }

  private void verifySNAP1933(String persistIndexes) throws Exception {
    try {
      Properties serverInfo = new Properties();
      // serverInfo.setProperty("log-level", "fine");
      startServerVMs(3, 0, null, serverInfo);
      // Start network server on the VMs
      int netPort1 = startNetworkServer(1, null, null);
      int netPort2 = startNetworkServer(2, null, null);
      int netPort3 = startNetworkServer(3, null, null);
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] {GfxdConstants.GFXD_PERSIST_INDEXES, persistIndexes});

      int maxValInsert = 1000;
      String tableNames[] = {"CODE_VALUE_1_1_T", "CODE_VALUE_1_1_F", "CODE_VALUE_1_99_T", "CODE_VALUE_1_99_F"
          , "CODE_VALUE_2_1_T", "CODE_VALUE_2_1_F", "CODE_VALUE_2_99_T", "CODE_VALUE_2_99_F"
          , "CODE_VALUE_3_1_T", "CODE_VALUE_3_1_F", "CODE_VALUE_3_99_T", "CODE_VALUE_3_99_F"};
      String indexNames[] = {"CODE_VALUE_1_1_T", "CODE_VALUE_1_1_F", "CODE_VALUE_1_99_T", "CODE_VALUE_1_99_F"
          ,"CODE_VALUE_2_1_T", "CODE_VALUE_2_1_F", "CODE_VALUE_2_99_T", "CODE_VALUE_2_99_F"
          ,"CODE_VALUE_3_1_T", "CODE_VALUE_3_1_F", "CODE_VALUE_3_99_T", "CODE_VALUE_3_99_F"};

      createTableSNAP1933(1, 1, true, tableNames[0], indexNames[0], maxValInsert);
      createTableSNAP1933(1, 1, false, tableNames[1], indexNames[1], maxValInsert);
      createTableSNAP1933(1, 99, true, tableNames[2], indexNames[2], maxValInsert);
      createTableSNAP1933(1, 99, false, tableNames[3], indexNames[3], maxValInsert);

      createTableSNAP1933(2, 1, true, tableNames[4], indexNames[4], maxValInsert);
      createTableSNAP1933(2, 1, false, tableNames[5], indexNames[5], maxValInsert);
      createTableSNAP1933(2, 99, true, tableNames[6], indexNames[6], maxValInsert);
      createTableSNAP1933(2, 99, false, tableNames[7], indexNames[7], maxValInsert);

      createTableSNAP1933(3, 1, true, tableNames[8], indexNames[8], maxValInsert);
      createTableSNAP1933(3, 1, false, tableNames[9], indexNames[9], maxValInsert);
      createTableSNAP1933(3, 99, true, tableNames[10], indexNames[10], maxValInsert);
      createTableSNAP1933(3, 99, false, tableNames[11], indexNames[11], maxValInsert);

      netPort1 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort2, 1,
          serverInfo, 0, "counta", persistIndexes);
      netPort2 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort3, 2,
          serverInfo, 1, "countb", persistIndexes);
      netPort3 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort1, 3,
          serverInfo, 2, "countc", persistIndexes);

      netPort1 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort2, 1,
          serverInfo, 3, "countd", persistIndexes);
      netPort2 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort3, 2,
          serverInfo, 4, "counte", persistIndexes);
      netPort3 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort1, 3,
          serverInfo, 5, "countf", persistIndexes);

      netPort1 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort2, 1,
          serverInfo, 6, "countg", persistIndexes);
      netPort2 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort3, 2,
          serverInfo, 7, "counth", persistIndexes);
      netPort3 = restartServerSNAP1933(tableNames, indexNames, maxValInsert, netPort1, 3,
          serverInfo, 8, "counti", persistIndexes);

      verifyAtLastSNAP1933(maxValInsert, tableNames, indexNames);
      dropIndexesSNAP1933(indexNames);
      dropTablesSNAP1933(tableNames);
      serverSQLExecute(1, "Drop schema " + " ODS RESTRICT");
    } finally {
      invokeInEveryVM(IndexPersistenceDUnit.class, "setSystemProperty",
          new Object[] {GfxdConstants.GFXD_PERSIST_INDEXES, "true"});
    }
  }

  public void testSNAP1933_persist_index_false() throws Exception {
    verifySNAP1933("false");
  }

  public void testSNAP1933_persist_index_true() throws Exception {
    verifySNAP1933("true");
  }
}
