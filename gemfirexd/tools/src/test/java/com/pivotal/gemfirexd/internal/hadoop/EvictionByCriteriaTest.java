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

package com.pivotal.gemfirexd.internal.hadoop;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;

import com.gemstone.gemfire.cache.CustomEvictionAttributes;
import com.gemstone.gemfire.cache.DataPolicy;
import com.gemstone.gemfire.cache.EvictionAction;
import com.gemstone.gemfire.cache.EvictionAlgorithm;
import com.gemstone.gemfire.cache.EvictionAttributes;
import com.gemstone.gemfire.cache.Operation;
import com.gemstone.gemfire.internal.cache.CachePerfStats;
import com.gemstone.gemfire.internal.cache.EntryEventImpl;
import com.gemstone.gemfire.internal.cache.GemFireCacheImpl;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.RegionEntry;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.ddl.GfxdEvictionCriteria;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeRegionKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.RegionKey;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.SQLVarchar;
import com.pivotal.gemfirexd.jdbc.JUnit4TestBase;
import org.apache.derbyTesting.junit.CleanDatabaseTestSetup;
import org.junit.After;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import static com.pivotal.gemfirexd.TestUtil.jdbcConn;
import static org.junit.Assert.*;

/**
 * Unit tests for GemFireXD EVICTION BY CRITERIA.
 */
@SuppressWarnings("unchecked")
public class EvictionByCriteriaTest extends JUnit4TestBase {

  private static final String HDFS_DIR = "./evictHDFS";

  public static Class<?> thisClass = EvictionByCriteriaTest.class;

  @BeforeClass
  public static void createHDFSStore() throws Exception {
    TestUtil.shutDown();
    TestUtil.setCurrentTestClass(thisClass);
    TestUtil.currentTest = "all";
    TestUtil.setupConnection();

    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();
    stmt.execute("create hdfsstore hdfsdata namenode 'localhost' homedir '"
        + HDFS_DIR + "' QUEUEPERSISTENT true");
  }

  @AfterClass
  public static void classTearDown() throws Exception {
    JUnit4TestBase.classTearDown();
    delete(new File(HDFS_DIR));
  }

  @After
  public void metadataCleanup() throws SQLException {
    dropTables();
  }

  public static void dropTables() throws SQLException {
    CleanDatabaseTestSetup.cleanDatabase(jdbcConn, false);
    GemFireCacheImpl cache = GemFireCacheImpl.getExisting();
    cache.getCachePerfStats().clearEvictionByCriteriaStatsForTest();
  }

  @SuppressWarnings("ResultOfMethodCallIgnored")
  private static void delete(File file) {
    if (!file.exists()) {
      return;
    }
    if (file.isDirectory()) {
      String[] contents = file.list();
      if (contents == null || contents.length == 0) {
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

  @Test
  public void testDDLSupport() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    // eviction of data more than 10 seconds old
    final String evictClause = "{fn TIMESTAMPDIFF(SQL_TSI_SECOND, "
        + "ts, CURRENT_TIMESTAMP)} > 10";
    stmt.execute("create table e.evictTable("
        + "id varchar(20) primary key, qty int, ts timestamp"
        + ")  " +  getOffHeapSuffix() + " partition by column(id) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria (" + evictClause
        + ") eviction frequency 8 seconds");

    LocalRegion lr = (LocalRegion)Misc.getRegion("/E/EVICTTABLE", true, false);
    GemFireContainer container = (GemFireContainer)lr.getUserAttribute();
    CustomEvictionAttributes evictionAttrs = lr.getAttributes()
        .getCustomEvictionAttributes();
    EvictionAttributes defEvictAttrs = lr.getAttributes()
        .getEvictionAttributes();
    // check attributes
    assertEquals(DataPolicy.HDFS_PERSISTENT_PARTITION, lr.getAttributes()
        .getDataPolicy());
    assertEquals(EvictionAlgorithm.LRU_HEAP, defEvictAttrs.getAlgorithm());
    assertEquals(EvictionAction.OVERFLOW_TO_DISK, defEvictAttrs.getAction());
    assertNotNull(evictionAttrs);
    assertEquals(0, evictionAttrs.getEvictorStartTime());
    assertEquals(8000, evictionAttrs.getEvictorInterval());
    GfxdEvictionCriteria criteria = (GfxdEvictionCriteria)evictionAttrs
        .getCriteria();
    assertEquals(evictClause, criteria.getPredicateString());

    // some inserts
    for (int i = 1; i <= 10; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }

    // check doEvict API
    EntryEventImpl event;
    RegionEntry entry;
    for (int i = 1; i <= 10; i++) {
      CompactCompositeRegionKey key = new CompactCompositeRegionKey(
          new SQLVarchar(Integer.toString(i)), container.getExtraTableInfo());
      event = EntryEventImpl.create(lr, Operation.LOCAL_DESTROY, key, null, null,
          false, null);
      final LocalRegion dataRegion = lr.getDataRegionForWrite(event,
          Operation.LOCAL_DESTROY);
      entry = lr.basicGetEntryForLock(dataRegion, key);
      assertFalse(entry.isMarkedForEviction());
      event.setRegionEntry(entry);
      assertFalse(criteria.doEvict(event));
    }

    // sleep for 12 secs
    Thread.sleep(12000);
    // more inserts
    for (int i = 11; i <= 20; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }

    // check that criteria should correctly filter out the first 10 entries
    @SuppressWarnings("unchecked")
    Iterator<Map.Entry<Object, Object>> evictIter = criteria
        .getKeysToBeEvicted(System.currentTimeMillis(), lr);
    int numExpectedKeys = 10;
    final boolean[] expectedKeys = new boolean[numExpectedKeys];
    while (evictIter.hasNext()) {
      Map.Entry<Object, Object> e = evictIter.next();
      DataValueDescriptor key = ((RegionKey)e.getKey()).getKeyColumn(0);
      int keyValue = key.getInt();
      assertFalse(expectedKeys[keyValue - 1]);
      expectedKeys[keyValue - 1] = true;
      numExpectedKeys--;
    }
    assertEquals(0, numExpectedKeys);

    // check the doEvict API again
    for (int i = 1; i <= 10; i++) {
      CompactCompositeRegionKey key = new CompactCompositeRegionKey(
          new SQLVarchar(Integer.toString(i)), container.getExtraTableInfo());
      event = EntryEventImpl.create(lr, Operation.LOCAL_DESTROY, key, null, null,
          false, null);
      final LocalRegion dataRegion = lr.getDataRegionForWrite(event,
          Operation.LOCAL_DESTROY);
      entry = lr.basicGetEntryForLock(dataRegion, key);
      event.setRegionEntry(entry);
      assertTrue(criteria.doEvict(event));
      entry.clearMarkedForEviction();
      assertTrue(criteria.doEvict(event));
    }
    for (int i = 11; i <= 20; i++) {
      CompactCompositeRegionKey key = new CompactCompositeRegionKey(
          new SQLVarchar(Integer.toString(i)), container.getExtraTableInfo());
      event = EntryEventImpl.create(lr, Operation.LOCAL_DESTROY, key, null, null,
          false, null);
      final LocalRegion dataRegion = lr.getDataRegionForWrite(event,
          Operation.LOCAL_DESTROY);
      entry = lr.basicGetEntryForLock(dataRegion, key);
      assertFalse(entry.isMarkedForEviction());
      event.setRegionEntry(entry);
      assertFalse(criteria.doEvict(event));
    }
  }

  @Test
  public void testEvictionService() throws Exception {
    setLogLevelForTest("config");

    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    // eviction of data more than 10 seconds old
    final String evictClause = "{fn TIMESTAMPDIFF(SQL_TSI_SECOND, "
        + "ts, CURRENT_TIMESTAMP)} > 10";
    stmt.execute("create table e.evictTable("
        + "id varchar(20) primary key, qty int, ts timestamp"
        + ")  " + getOffHeapSuffix() + "  partition by column(id) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria (" + evictClause
        + ") eviction frequency 5 seconds");

    LocalRegion lr = (LocalRegion)Misc.getRegion("/E/EVICTTABLE", true, false);
    //GemFireContainer container = (GemFireContainer)lr.getUserAttribute();
    CustomEvictionAttributes evictionAttrs = lr.getAttributes()
        .getCustomEvictionAttributes();
    EvictionAttributes defEvictAttrs = lr.getAttributes()
        .getEvictionAttributes();
    // check attributes
    assertEquals(DataPolicy.HDFS_PERSISTENT_PARTITION, lr.getAttributes()
        .getDataPolicy());
    assertEquals(EvictionAlgorithm.LRU_HEAP, defEvictAttrs.getAlgorithm());
    assertEquals(EvictionAction.OVERFLOW_TO_DISK, defEvictAttrs.getAction());
    assertNotNull(evictionAttrs);
    assertEquals(0, evictionAttrs.getEvictorStartTime());
    assertEquals(5000, evictionAttrs.getEvictorInterval());
    GfxdEvictionCriteria criteria = (GfxdEvictionCriteria)evictionAttrs
        .getCriteria();
    assertEquals(evictClause, criteria.getPredicateString());

    // some inserts
    for (int i = 1; i <= 10; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }

    // sleep for 17 secs
    Thread.sleep(17000);
    // more inserts
    long then = System.currentTimeMillis();
    for (int i = 11; i <= 20; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }

    assertTrue(stmt.execute("select * from e.evictTable"));
    ResultSet rs = stmt.getResultSet();

    Set ids = new HashSet();
    while (rs.next()) {
      int id = rs.getInt("id");
      logger.info("The id is " + id);
      ids.add(id);
    }
    assertEquals(10, ids.size());
    // 0 - 10 should be evicted.
    for (int i = 11; i <= 20; i++) {
      assertTrue(ids.contains(i));
    }
    assertEquals(10, lr.getCachePerfStats().getEvictions());
    long delta = System.currentTimeMillis() - then;
    if (delta < 3000) {
      assertEquals(0, lr.getCachePerfStats().getEvictionsInProgress());
      assertEquals(30, lr.getCachePerfStats().getEvaluations());
    }
  }

  @Test
  public void testBug49900() throws Exception {
    Connection conn = jdbcConn;
    Statement st = conn.createStatement();

    try {
      // EVICTION BY CRITERIA for replicated persistent non-HDFS table
      st.execute("create table t1 (col1 int)  " + getOffHeapSuffix()
          + " replicate persistent eviction by criteria (col1 > 0) evict incoming");
      fail("EVICTION BY CRITERIA is not supported for non-HDFS tables");
    }
    catch (SQLException e) {
      if (!(e.getSQLState().equals("0A000") && e.getMessage().equals(
          "Eviction By Criteria is not supported for non-HDFS Table."))) {
        throw e;
      }
    }

    try {
      // EVICTION BY CRITERIA for replicated non-HDFS table
      st.execute("create table t2 (col1 int)  " + getOffHeapSuffix()
          + " replicate eviction by criteria (col1 > 0) evict incoming");
      fail("EVICTION BY CRITERIA is not supported for non-HDFS tables");
    }
    catch (SQLException e) {
      if (!(e.getSQLState().equals("0A000") && e.getMessage().equals(
          "Eviction By Criteria is not supported for non-HDFS Table."))) {
        throw e;
      }
    }

    try {
      // EVICTION BY CRITERIA for partitioned persistent non-HDFS table
      st.execute("create table t3 (col1 int)  " + getOffHeapSuffix()
          + " partition by column (col1) persistent "
          + "eviction by criteria (col1 > 0) evict incoming");
      fail("EVICTION BY CRITERIA is not supported for non-HDFS tables");
    }
    catch (SQLException e) {
      if (!(e.getSQLState().equals("0A000") && e.getMessage().equals(
          "Eviction By Criteria is not supported for non-HDFS Table."))) {
        throw e;
      }
    }

    try {
      // EVICTION BY CRITERIA for partitioned non-HDFS table
      st.execute("create table t4 (col1 int)  " + getOffHeapSuffix()
          + " partition by column (col1) eviction by criteria (col1 > 0) evict incoming");
      fail("EVICTION BY CRITERIA is not supported for non-HDFS tables");
    }
    catch (SQLException e) {
      if (!(e.getSQLState().equals("0A000") && e.getMessage().equals(
          "Eviction By Criteria is not supported for non-HDFS Table."))) {
        throw e;
      }
    }

    try {
      // EVICTION BY CRITERIA for partitioned non-HDFS table
      st.execute("create table t5 (col1 varchar(20) primary key, col2 int)  "
          + getOffHeapSuffix() + " partition by column (col1) "
          + "eviction by lrucount 1 evictaction destroy hdfsstore (hstore)");
      fail("EVICTION action is not supported for HDFS tables");
    }
    catch (SQLException e) {
      if (!(e.getSQLState().equals("0A000") && e.getMessage().equals(
          "LRU eviction is not supported for HDFS tables."))) {
        throw e;
      }
    }
  }

  /**
   * Check by querying the table if everything is right.
   * @throws Exception
   */
  @Test
  public void testEvictionServiceIndex() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    // eviction of data more than 10 seconds old
    final String evictClause = "{fn TIMESTAMPDIFF(SQL_TSI_SECOND, "
        + "ts, CURRENT_TIMESTAMP)} > 10";
    stmt.execute("create table e.evictTable("
        + "id varchar(20) primary key, qty int, ts timestamp"
        + ")  " + getOffHeapSuffix() + "  partition by column(id) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria (" + evictClause
        + ") eviction frequency 5 seconds");

    LocalRegion lr = (LocalRegion)Misc.getRegion("/E/EVICTTABLE", true, false);
    //GemFireContainer container = (GemFireContainer)lr.getUserAttribute();
    CustomEvictionAttributes evictionAttrs = lr.getAttributes()
        .getCustomEvictionAttributes();
    EvictionAttributes defEvictAttrs = lr.getAttributes()
        .getEvictionAttributes();
    // check attributes
    assertEquals(DataPolicy.HDFS_PERSISTENT_PARTITION, lr.getAttributes()
        .getDataPolicy());
    assertEquals(EvictionAlgorithm.LRU_HEAP, defEvictAttrs.getAlgorithm());
    assertEquals(EvictionAction.OVERFLOW_TO_DISK, defEvictAttrs.getAction());
    assertNotNull(evictionAttrs);
    assertEquals(0, evictionAttrs.getEvictorStartTime());
    assertEquals(5000, evictionAttrs.getEvictorInterval());
    GfxdEvictionCriteria criteria = (GfxdEvictionCriteria)evictionAttrs
        .getCriteria();
    assertEquals(evictClause, criteria.getPredicateString());

    // some inserts
    for (int i = 1; i <= 10; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }

    // sleep for 17 secs
    Thread.sleep(17000);
    // more inserts
    for (int i = 11; i <= 20; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }

    // default query
    assertTrue(stmt.execute("select * from e.evictTable"));
    ResultSet rs = stmt.getResultSet();

    Set ids = new HashSet();
    while (rs.next()) {
      int id = rs.getInt("id");
      ids.add(id);
    }
    assertEquals(10, ids.size());
    // 0 - 10 should be evicted.
    for (int i = 11; i <= 20; i++) {
      assertTrue(ids.contains(i));
    }
    
    // query with hdfs true
    assertTrue(stmt.execute("select * from e.evictTable -- GEMFIREXD-PROPERTIES queryHDFS=true"));
    rs = stmt.getResultSet();

    ids = new HashSet();
    while (rs.next()) {
      int id = rs.getInt("id");
      ids.add(id);
    }
    assertEquals(20, ids.size());
    // 0 - 10 should be evicted.
    for (int i = 1; i <= 20; i++) {
      assertTrue(ids.contains(i));
    }
    
    
    //query with hdfs false
    assertTrue(stmt.execute("select * from e.evictTable -- GEMFIREXD-PROPERTIES queryHDFS=false"));
    rs = stmt.getResultSet();

    ids = new HashSet();
    while (rs.next()) {
      int id = rs.getInt("id");
      ids.add(id);
    }
    assertEquals(10, ids.size());
    // 0 - 10 should be evicted.
    for (int i = 11; i <= 20; i++) {
      assertTrue(ids.contains(i));
    }    
    
    assertEquals(10, lr.getCachePerfStats().getEvictions());
    assertEquals(0, lr.getCachePerfStats().getEvictionsInProgress());
    // TODO: This validation may change
    //assertEquals(30, lr.getCachePerfStats().getEvaluations());
  }

  @Test
  public void testEvictIncomingDDLSupport() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    // eviction of data more than 10 seconds old
    final String evictClause = "qty > 100";
    stmt.execute("create table e.evictTable("
        + "id varchar(20) primary key, qty int, ts timestamp"
        + ")  " + getOffHeapSuffix() + "  partition by column(id) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria (" + evictClause + ") EVICT INCOMING ");

    LocalRegion lr = (LocalRegion)Misc.getRegion("/E/EVICTTABLE", true, false);
    CustomEvictionAttributes evictionAttrs = lr.getAttributes()
        .getCustomEvictionAttributes();
    EvictionAttributes defEvictAttrs = lr.getAttributes()
        .getEvictionAttributes();
    // check attributes
    assertEquals(DataPolicy.HDFS_PERSISTENT_PARTITION, lr.getAttributes()
        .getDataPolicy());
    assertEquals(EvictionAlgorithm.LRU_HEAP, defEvictAttrs.getAlgorithm());
    assertEquals(EvictionAction.OVERFLOW_TO_DISK, defEvictAttrs.getAction());
    assertNotNull(evictionAttrs);
    GfxdEvictionCriteria criteria = (GfxdEvictionCriteria)evictionAttrs
        .getCriteria();
    assertEquals(evictClause, criteria.getPredicateString());
    assertTrue(evictionAttrs.isEvictIncoming());

    // some inserts
    for (int i = 1; i <= 20; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }
    
    assertEquals(10, lr.size());
    CachePerfStats stats = lr.getCachePerfStats();
    assertEquals(10, stats.getEvictions());
    assertEquals(0, stats.getEvictionsInProgress());
    assertEquals(20, stats.getEvaluations());
  }

  @Test
  public void testEvictIncomingQueryHDFS() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table e.evictTable( id int primary key, qty int, abc int )  "
        + getOffHeapSuffix() + " partition by column(id) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( qty > 100 ) EVICT INCOMING ");

    stmt.execute("create index idx on e.evictTable (qty, abc)");
    // some inserts
    for (int i = 1; i <= 20; i++) 
    { 
      stmt.executeUpdate("insert into e.evictTable values (" + i + ", "
          + (i * 10) +", " + (i * 10) + ")");
    }

    PreparedStatement ps = conn
        .prepareStatement("select * from e.evictTable -- GEMFIREXD-PROPERTIES queryHDFS=false \n where qty=?");
    for (int i = 1; i < 20; i++) 
    {
      ps.setInt(1, i * 10);
      ResultSet rs = ps.executeQuery();
      if (i > 10) {
        assertFalse(rs.next());
      } else {
        assertTrue(rs.next());
      }
    }

    ps = conn.prepareStatement("select * from e.evictTable -- GEMFIREXD-PROPERTIES queryHDFS=false \n where id=?");
    for (int i = 1; i < 20; i++) {
      ps.setInt(1, i);
      ResultSet rs = ps.executeQuery();
      if (i > 10) {
        assertFalse(rs.next());
      } else {
        assertTrue(rs.next());
      }
    }
    
    ps = conn
        .prepareStatement("select * from e.evictTable -- GEMFIREXD-PROPERTIES queryHDFS=true  \n where id=?");
    for (int i = 1; i < 20; i++) {
      ps.setInt(1, i);
      ResultSet rs = ps.executeQuery();
      assertTrue(rs.next());
    }
    
    ResultSet rs1 = stmt.executeQuery("select * from e.evictTable -- GEMFIREXD-PROPERTIES queryHDFS=true");
    Set ids = new HashSet();
    while(rs1.next()) {
      int id = rs1.getInt("id");
      ids.add(id);
    }
    assertEquals(20, ids.size());
    
    rs1 = stmt.executeQuery("select * from e.evictTable -- GEMFIREXD-PROPERTIES queryHDFS=false");
    ids = new HashSet();
    while(rs1.next()) {
      int id = rs1.getInt("id");
      ids.add(id);
    }
    assertEquals(10, ids.size());
    
    rs1 = stmt.executeQuery("select * from e.evictTable -- GEMFIREXD-PROPERTIES queryHDFS=false  \n where qty > 100");
    ids = new HashSet();
    while(rs1.next()) {
      int id = rs1.getInt("id");
      ids.add(id);
    }
    assertEquals(0, ids.size());
    
  }

  @Test
  public void testEvictIncomingWithPartitionKey() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table e.evictTable( id int primary key, partkey int not null) "
        + getOffHeapSuffix() + " partition by column(partkey) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( id > 10 ) EVICT INCOMING ");

    // some inserts
    stmt.executeUpdate("insert into e.evictTable values (20, 200 )");

    try {
      stmt.executeUpdate("insert into e.evictTable values (20, 201)");
      fail("Did not get expected constraint violation exception");
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) {
        throw e;
      }
    }
    
    stmt.executeUpdate("insert into e.evictTable values (30, 201 )");
    stmt.executeUpdate("delete from e.evictTable where id=30");

    // Bounce the system.
    TestUtil.shutDown();

    // Delete the krf to force recovery from the crf
    String currDir = System.getProperty("user.dir");
    File cdir = new File(currDir);
    String[] files = cdir.list();
    if (files != null) {
      for(String file : files) {
        if (file.matches(".*GFXD-DEFAULT-DISKSTORE.*krf")) {
          File f = new File(file);
          f.delete();
        }
      }
    }

    // restart
    TestUtil.setupConnection();
  }

  @Test
  public void testEvictIncomingWithUniqueIndex() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table e.evictTable( id int primary key, "
        + "partkey int not null, qty int, constraint uq unique (partkey, qty)) "
        + getOffHeapSuffix() + "  partition by column(partkey) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( qty > 100 ) EVICT INCOMING ");

    // some inserts
    for (int i = 1; i <= 20; i++) {
      stmt.executeUpdate("insert into e.evictTable values (" + i + ", " + (i * 10) +", " + (i * 100) + ")");
    }

    try {
      stmt.executeUpdate("insert into e.evictTable values (21, 200, 2000)");
      fail("Did not get expected constraint violation exception");
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) {
        throw e;
      }
    }
  }

  @Test
  public void testEvictIncomingWithUniqueIndexDelete() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table e.evictTable( id int primary key, "
        + "partkey int not null, qty int, constraint uq unique (partkey, qty)) "
        + getOffHeapSuffix() + " partition by column(partkey) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( qty > 100 ) EVICT INCOMING ");

    stmt.execute("create table e.simpleTable( id int primary key, "
        + "partkey int not null, qty int, constraint uq2 unique (partkey, qty)) "
        + getOffHeapSuffix() + " partition by column(partkey)");

    // some inserts
    for (int i = 20; i <= 20; i++) {
      stmt.executeUpdate("insert into e.evictTable values (" + i + ", " + (i * 10) +", " + (i * 100) + ")");
      stmt.executeUpdate("insert into e.simpleTable values (" + i + ", " + (i * 10) +", " + (i * 100) + ")");
    }

    try {
      stmt.executeUpdate("insert into e.evictTable values (21, 200, 2000)");
      fail("Did not get expected constraint violation exception");
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) {
        throw e;
      }
    }
    try {
      stmt.executeUpdate("insert into e.simpleTable values (21, 200, 2000)");
      fail("Did not get expected constraint violation exception");
    } catch (SQLException e) {
      if (!"23505".equals(e.getSQLState())) {
        throw e;
      }
    }
    stmt.execute("delete from e.evictTable where id= 20");
    stmt.execute("delete from e.simpleTable where id= 20");
    ResultSet rs = stmt.executeQuery("select * from e.evictTable  -- GEMFIREXD-PROPERTIES queryHDFS=true \n where id = 20 ");
    assertFalse(rs.next());

    rs = stmt.executeQuery("select * from e.simpleTable where id = 20");
    assertFalse(rs.next());

    stmt.executeUpdate("insert into e.simpleTable values (21, 200, 2000)");
    stmt.executeUpdate("insert into e.evictTable values (21, 200, 2000)");
  }

  /**
   * Table with unique constraint
   * Insert one row
   * Update the one unique column
   * delete the row
   * try inserting a row with same unique value again
   * @throws Exception
   */
  @Test
  public void testEvictIncomingWithUniqueIndexUpdateDelete() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table e.evictTable( id int primary key, "
        + "partkey int not null, qty int, constraint uq unique (partkey, qty)) "
        + getOffHeapSuffix() + " partition by column(partkey) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( qty > 100 ) EVICT INCOMING ");

    stmt.execute("create table e.simpleTable( id int primary key, "
        + "partkey int not null, qty int, constraint uq2 unique (partkey, qty)) "
        + getOffHeapSuffix() + " partition by column(partkey)");

    int i = 20;
    stmt.executeUpdate("insert into e.evictTable values (" + i + ", " + (i * 10) +", " + (i * 100) + ")");
    //stmt.executeUpdate("insert into e.simpleTable values (" + i + ", " + (i * 10) +", " + (i * 100) + ")");

    stmt.executeUpdate("update e.evictTable set qty=300 where id=20");
    //stmt.executeUpdate("insert into e.simpleTable values (" + i + ", " + (i * 10) +", " + (i * 100) + ")");

    stmt.execute("delete from e.evictTable where id= 20");
    //stmt.execute("delete from e.simpleTable where id= 20");
    ResultSet rs = stmt.executeQuery("select * from e.evictTable  -- GEMFIREXD-PROPERTIES queryHDFS=true \n where id = 20 ");
    assertFalse(rs.next());

    //rs = stmt.executeQuery("select * from e.simpleTable where id = 20");
    //assertFalse(rs.next());

    //stmt.executeUpdate("insert into e.simpleTable values (21, 200, 2000)");
    stmt.executeUpdate("insert into e.evictTable values (21, 200, 2000)");
  }

  protected String getOffHeapSuffix() {
    return " ";
  }
}
