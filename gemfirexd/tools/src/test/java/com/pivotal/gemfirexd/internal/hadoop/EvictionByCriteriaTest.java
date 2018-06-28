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
import java.sql.SQLFeatureNotSupportedException;
import java.sql.Statement;
import java.sql.Time;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

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

  protected static Class<?> thisClass = EvictionByCriteriaTest.class;

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
  public void dropTables() throws SQLException {
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
  public void testEvictionService_AlterFrequency() throws Exception {
    setLogLevelForTest("config");

    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    final String evictClause = "qty > 500";
    stmt.execute("create table e.evictTable("
        + "id varchar(20) primary key, qty int, ts timestamp"
        + ")  " + getOffHeapSuffix() + "  partition by column(id) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria (" + evictClause
        + ") eviction frequency 8 seconds");

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
    assertEquals(0, evictionAttrs.getEvictorStartTime());
    assertEquals(8000, evictionAttrs.getEvictorInterval());
    GfxdEvictionCriteria criteria = (GfxdEvictionCriteria)evictionAttrs
        .getCriteria();
    assertEquals(evictClause, criteria.getPredicateString());

    // some inserts
    for (int i = 1; i <= 100; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }
    
    //no eviction yet. Hence all the keys will be present
    assertTrue(stmt.execute("select * from e.evictTable"));
    ResultSet rs = stmt.getResultSet();

    Set ids = new HashSet();
    while (rs.next()) {
      int id = rs.getInt("id");
      ids.add(id);
    }
    assertEquals(100, ids.size());
    for (int i = 1; i <= 100; i++) {
      assertTrue(ids.contains(i));
    }
    
    //allow eviction to happen
    Thread.sleep(17000);
    assertTrue(stmt.execute("select * from e.evictTable"));
    rs = stmt.getResultSet();

    ids = new HashSet();
    while (rs.next()) {
      int id = rs.getInt("id");
      ids.add(id);
    }
    //entries form 51 to 100 will be evicted
    assertEquals(50, ids.size());
    for (int i = 1; i <= 50; i++) {
      assertTrue(ids.contains(i));
    }
    
    //another entries added
    for (int i = 101; i <= 200; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }
    
    
    assertTrue(stmt.execute("select * from e.evictTable"));
    rs = stmt.getResultSet();

    ids = new HashSet();
    while (rs.next()) {
      int id = rs.getInt("id");
      ids.add(id);
    }
    //no eviction yet hence previous 50 + recently added 100 entries present
    assertEquals(150, ids.size());
    
    //eviction start time and frequency is getting changed
    SimpleDateFormat sdf = new SimpleDateFormat();
    sdf.setTimeZone(TimeZone.getTimeZone("GMT"));
    String gmtStrDate = sdf.format(Calendar.getInstance().getTime());
    Date date = new Date(gmtStrDate);
    stmt.execute("ALTER TABLE e.evictTable SET EVICTION FREQUENCY 5 SECONDS START T '" + new Time(date.getTime()) + "'");
    lr = (LocalRegion)Misc.getRegion("/E/EVICTTABLE", true, false);
    evictionAttrs = lr.getAttributes()
        .getCustomEvictionAttributes();
    assertEquals(5000, evictionAttrs.getEvictorInterval());
    criteria = (GfxdEvictionCriteria)evictionAttrs
        .getCriteria();
    assertEquals(evictClause, criteria.getPredicateString());
    
    //Sleep to allow eviction to happen by previous frequency of 8 sec. So 100(101-200) entries will be evicted
    Thread.sleep(17000);
    
    //another 100 entries added
    for (int i = 201; i <= 300; i++) {
      stmt.executeUpdate("insert into e.evictTable values ('" + i + "', "
          + (i * 10) + ", CURRENT_TIMESTAMP)");
    }

    //this time new eviction frequency 5 sec will be used and and entries from 201 to 300 will be evicted.
    Thread.sleep(10000);
    assertTrue(stmt.execute("select * from e.evictTable"));
    rs = stmt.getResultSet();

    ids = new HashSet();
    while (rs.next()) {
      int id = rs.getInt("id");
      ids.add(id);
    }
    assertEquals(50, ids.size());
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
  
  /**
   * Table with unique constraint
   * Insert one row
   * Insert again row with same unique value
   * it should throw exception
   * delete the row
   * insert the row with same unique keys again. it should succeed
   * @throws Exception
   */
  @Test
  public void testEvictIncomingWithUniqueIndexUpdateDelete2() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk "
        + "primary key (cid, sid), constraint qty_ck check (qty>=0), "
        + "constraint avail_ch check (availQty>=0 and availQty<=qty)) "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "EVICTION BY CRITERIA ( qty > 500  ) EVICT INCOMING ");

    stmt.executeUpdate("insert into trade.portfolio  values(3, 120, 1592, 1592, 14264.32000000000000000000 ,18)");
    try {
      stmt.executeUpdate("insert into trade.portfolio  values(3, 120, 1374, 1374, 12311.04000000000000000000, 18)");
      fail("failed");
    }
    catch(Exception c) {
      c.printStackTrace();
    }
    
    stmt.execute("delete from trade.portfolio where cid = 3 and sid = 120 and tid = 18");
    ResultSet rs = stmt.executeQuery("select * from trade.portfolio  -- GEMFIREXD-PROPERTIES queryHDFS=true ");
    assertFalse(rs.next());
    stmt.executeUpdate("insert into trade.portfolio  values(3, 120, 1374, 1374, 12311.04000000000000000000, 8)");
  }

  @Test
  public void testEvictIncomingWithUniqueIndexUpdate() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table e.evictTable( id int primary key, "
        + "partkey int not null, qty int, constraint uq unique (partkey, qty)) "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( qty > 100 ) EVICT INCOMING ");

    stmt.execute("create table e.simpleTable( id int primary key, "
        + "partkey int not null, qty int, constraint uq2 unique (partkey, qty)) "
        + getOffHeapSuffix());

    // some inserts
    for (int i = 20; i <= 20; i++) {
      stmt.executeUpdate("insert into e.evictTable values (" + i + ", " + (i * 10) +", " + (i * 100) + ")");
      stmt.executeUpdate("insert into e.simpleTable values (" + i + ", " + (i * 10) +", " + (i * 100) + ")");
    }
    //update trade.customers set
    //stmt.executeUpdate("update e.evictTable set qty=201 where id = 20");
   // stmt.executeUpdate("update e.simpleTable set qty=201 where id = 20");
    
    stmt.executeUpdate("update e.evictTable set qty=2002,partkey=202 where id = 20");
    stmt.executeUpdate("update e.simpleTable set qty=2002,partkey=202 where id = 20");
    
    ResultSet rs = stmt.executeQuery("select * from e.evictTable  -- GEMFIREXD-PROPERTIES queryHDFS=true \n where id = 20 ");
    assertTrue(rs.next());
    assertEquals(202,rs.getInt(2));
    assertEquals(2002,rs.getInt(3));
  }
  
  /**
   * 1. Insert a row with partition column, and primary keys as a combination of two keys so
   *    that it gets evicted
   * 2. Insert again a row with the same primary key, catch exception
   * 3. Validate there is no data in operational
   * @throws Exception
   */
  @Test
  public void testEvictIncomingWithUniqueIndexUpdate2() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "subTotal decimal(30,20), tid int, constraint portf_pk "
        + "primary key (cid, sid), constraint qty_ck check (qty>=0), "
        + "constraint avail_ch check (availQty>=0 and availQty<=qty)) "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "EVICTION BY CRITERIA ( qty > 500  ) EVICT INCOMING ");

    // some inserts
    stmt.executeUpdate("insert into trade.portfolio values ( 104 , 91 , 1568 , 1568 , 53500.16000000000000000000 , 6)");
    //update trade.customers set
    //stmt.executeUpdate("update e.evictTable set qty=201 where id = 20");
   // stmt.executeUpdate("update e.simpleTable set qty=201 where id = 20");
    try {
      stmt.executeUpdate("insert into trade.portfolio values ( 104 , 91 , 2000 , 2000 , 68240.00000000000000000000 ,6)");
      fail("failed");
    }
    catch(Exception e){
      e.printStackTrace();
    }
    try {
      stmt.executeUpdate("insert into trade.portfolio values ( 104 , 91 , 247 , 247 , 8427.64000000000000000000, 6)");
      fail("failed");
    }
    catch(Exception e){
      e.printStackTrace();
    }
    try {
      stmt.executeUpdate("insert into trade.portfolio values ( 104 , 91 , 1408 , 1408 , 48040.96000000000000000000, 6)");
      fail("failed");
    }
    catch(Exception e){
      e.printStackTrace();
    }
    
    String[] arr = {"insert into trade.portfolio values ( 104 , 91 , 1090 , 1090 , 37190.80000000000000000000 ,6)",
        "insert into  trade.portfolio  values (104 , 91 , 659 , 659 , 22485.08000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 1943 , 1943 , 66295.16000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 1051 , 1051 , 35860.12000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 1905 , 1905 , 64998.60000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 756 , 756 , 25794.72000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 432 , 432 , 14739.84000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 438 , 438 , 14944.56000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 633 , 633 , 21597.96000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 755 , 755 , 25760.60000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 1737 , 1737 , 59266.44000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 691 , 691 , 23576.92000000000000000000, 6)",
        "insert into  trade.portfolio  values (104 , 91 , 1968 , 1968 , 67148.16000000000000000000, 6)"};
    
    for(String st: arr) {
      try {
        stmt.executeUpdate(st);
        fail("failed");
      }
      catch(Exception e){
        e.printStackTrace();
      }
    }
    
    ResultSet rs = stmt.executeQuery("select * from trade.portfolio where qty > 500");
    assertFalse(rs.next());
    rs = stmt.executeQuery("select * from trade.portfolio -- GEMFIREXD-PROPERTIES queryHDFS=true \n where qty > 500 ");
    assertTrue(rs.next());
    System.out.println(rs.getInt("qty"));
    LocalRegion lr = (LocalRegion)Misc.getRegion("/TRADE/PORTFOLIO", true, false);
    System.out.println(lr.size());
    for(Object o: lr.entrySet()) {
      System.out.println("Entry : " + o);
    }
  }
  
  /**
   * For cheetah, foreign key constraint won't be supported with custom eviction to HDFS.
   * Defect # 49367/49452.
   */
  @Test
  public void testEvictIncomingWithForeignKey() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name int, primary key (cid))  "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( cid > 5 ) EVICT INCOMING ");
    
    String expectedMessage = "Foreign key constraint is not supported with custom eviction criteria for HDFS tables.";
    try {
      stmt.execute("create table trade.networth (netid int not null, "
          + "cid int not null, cash decimal (30, 20), constraint netw_pk "
          + "primary key (netid), constraint cust_newt_fk foreign key (cid) "
          + "references trade.customers (cid) on delete restrict) "
          + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
          + "eviction by criteria ( cash > 1000 ) EVICT INCOMING ");
      fail("Expected SQLFeatureNotSupportedException as FK is not supported with custom eviction to HDFS.");
    } catch (SQLFeatureNotSupportedException e) {
      assertTrue(e.getMessage().equals(expectedMessage));
    } 
    
    stmt.execute("create table trade.networth (netid int not null, "
        + "cid int not null, cash decimal (30, 20), constraint netw_pk "
        + "primary key (netid), constraint cust_newt_fk foreign key (cid) "
        + "references trade.customers (cid) on delete restrict) "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5");
  }
  
  
  /**
   * Insert 3 rows. Two for NonOperational and One for operational
   * Update the one non-operational such that it becomes operational
   * Update the second non-operational such that it remains non-operational
   * @throws Exception
   */
  @Test
  public void testEvictIncomingWithLocalIndexes() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name int, addr int, primary key (cid)) "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( cust_name > 5 ) EVICT INCOMING ");
    
    // index on cust_name and addr
    stmt.execute("create index idx1 on trade.customers (cust_name)");
    stmt.execute("create index idx2 on trade.customers (addr)");
    
    // some inserts
    // will be evicted
    stmt.executeUpdate("insert into trade.customers values (" + 12 + ", " + (12 * 10) +", " + (12 * 100) + ")");
    stmt.executeUpdate("insert into trade.customers values (" + 13 + ", " + (13 * 10) +", " + (13 * 100) + ")");
    // will not be evicted
    stmt.executeUpdate("insert into trade.customers values (" + 1 + ", " + (1 * 1) +", " + (1 * 100) + ")");
    // check if inserting an evicted row gives error
    try {
      stmt.executeUpdate("insert into trade.customers values (" + 12 + ", " + (12 * 10) +", " + (12 * 100) + ")");
      fail("Expected Exception as the row was already inserted. ");
    }
    catch (Exception e) {
      
    }
    
    // verify the operational data.
    // 1 1 100
    ResultSet rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=1");
    assertTrue(rs.next()); 
    assertEquals(1, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=1");
    assertTrue(rs.next());
    assertEquals(1, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=100");
    assertTrue(rs.next());
    assertEquals(100, rs.getInt("addr"));

    // 12, 120, 1200
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=12");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=120");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1200");
    assertFalse(rs.next());
    // 13, 130, 1300    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=13");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=130");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1300");
    assertFalse(rs.next());
    
    // verify the non operational data.
    // 12,120,1200
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=12");
    assertTrue(rs.next());
    assertEquals(12, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=120");
    assertTrue(rs.next());
    assertEquals(120, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1200");
    assertTrue(rs.next());
    assertEquals(1200, rs.getInt("addr"));
    // 13,130,1300    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=13");
    assertTrue(rs.next());
    assertEquals(13, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=130");
    assertTrue(rs.next());
    assertEquals(130, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1300");
    assertTrue(rs.next());
    assertEquals(1300, rs.getInt("addr"));
    
    
    // making a change to evicted row such that EvictionCriteria is not satisfied.
    stmt.executeUpdate("update trade.customers set cust_name=4 where cid=12");
    
    // it should be back in operational data
    // 12,4,1200
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=12");
    assertTrue(rs.next());
    assertEquals(12, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=4");
    assertTrue(rs.next());
    assertEquals(4, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1200");
    assertTrue(rs.next());
    assertEquals(1200, rs.getInt("addr"));
    
    // making a change to evicted row such that EvictionCriteria is satisfied.
    stmt.executeUpdate("update trade.customers set cust_name=15 where cid=13");
    // 13, 15, 1300 in non-operational    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=13");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=15");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1300");
    assertFalse(rs.next());
    
    // 13,15,1300    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=13");
    assertTrue(rs.next());
    assertEquals(13, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=15");
    assertTrue(rs.next());
    assertEquals(15, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1300");
    assertTrue(rs.next());
    assertEquals(1300, rs.getInt("addr"));
    
    LocalRegion lr = (LocalRegion)Misc.getRegion("/TRADE/CUSTOMERS", true, false);
    //TODO: check if this assumtion is correct!
    //assertEquals(2, lr.size());
  }
  
  /**
   * 1. Insert one row which satisfies eviction criteria
   * 2. Make change such that it doesn't satisfy eviction criteria
   * 3. Verify
   * 4. Delete the row
   * 5. Verify
   * 6. Insert again
   * 7. Verify
   * 8. Delete 
   * 9. Verify
   * 
   * @throws Exception
   */
  @Test
  public void testEvictIncomingWithLocalIndexesMultipleOperations() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name int, addr int, primary key (cid)) "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( cust_name > 5 ) EVICT INCOMING ");
    
    // index on cust_name and addr
    stmt.execute("create index idx1 on trade.customers (cust_name)");
    stmt.execute("create index idx2 on trade.customers (addr)");
    
    // insert that will be evicted
    stmt.executeUpdate("insert into trade.customers values (" + 12 + ", " + (12 * 10) +", " + (12 * 100) + ")");
    ResultSet rs = null;
    // making a change to evicted row such that EvictionCriteria is not satisfied.
    stmt.executeUpdate("update trade.customers set cust_name=4 where cid=12");
    //Verify 
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=12");
    assertTrue(rs.next());
    assertEquals(12, rs.getInt("cid"));
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=4");
    assertTrue(rs.next());
    assertEquals(4, rs.getInt("cust_name"));
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1200");
    assertTrue(rs.next());
    assertEquals(1200, rs.getInt("addr"));
    
    //Delete the row
    stmt.execute("delete from trade.customers where cid=12");
    // Verify
    // operational
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=12");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=4");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1200");
    assertFalse(rs.next());
    // Non-Operational
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=12");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=4");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1200");
    assertFalse(rs.next());
    // Insert the same row again
    stmt.executeUpdate("insert into trade.customers values (" + 12 + ", " + (12 * 10) +", " + (12 * 100) + ")");
    // Verify
    // Operational
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=12");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=4");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1200");
    assertFalse(rs.next());
    
    // Non-operational
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=12");
    assertTrue(rs.next());
    assertEquals(12, rs.getInt("cid"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=120");
    assertTrue(rs.next());
    assertEquals(120, rs.getInt("cust_name"));
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1200");
    assertTrue(rs.next());
    assertEquals(1200, rs.getInt("addr"));
    
    // Delete the row
    stmt.execute("delete from trade.customers where cid=12");
    // Verify
    // Operational
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=12");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cust_name=4");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=false \n where addr=1200");
    assertFalse(rs.next());
    // Non_operational
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=12");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cust_name=4");
    assertFalse(rs.next());
    
    rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true \n where addr=1200");
    assertFalse(rs.next());
    
    LocalRegion lr = (LocalRegion)Misc.getRegion("/TRADE/CUSTOMERS", true, false);
    assertEquals(0, lr.size());
  }

  @Test
  public void testEvictIncomingWithLocalIndexesFailedOperation() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.portfolio (cid int not null, "
        + "sid int not null, qty int not null, availQty int not null, "
        + "constraint portf_pk primary key (cid, sid), "
        + "constraint qty_ck check (qty>=0), "
        + "constraint avail_ch check (availQty>=0 and availQty<=qty)) "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( qty > 100 ) EVICT INCOMING ");
    
    // some inserts
    stmt.executeUpdate("insert into trade.portfolio values (" + 1 + ", " + 2 +", " + (2 * 1000) + " , " +  2 * 100 + ")");
    // ensure that it goes to hdfs
    // TODO: Use flush operation instead of waiting
    Thread.sleep(10000);
    try {
      stmt.executeUpdate("insert into trade.portfolio values (" + 1 + ", " + 2 +", " + (2 * 10) + " , " +  2 * 1 + ")");
      fail("Expected Exception as the row was already inserted in the table.");
    }
    catch ( Exception e) {
    }
    
    // Verify the table
    ResultSet rs = null;
    rs = stmt.executeQuery("select * from trade.portfolio -- GEMFIREXD-PROPERTIES queryHDFS=false \n where cid=1");
    assertFalse(rs.next());
    rs = stmt.executeQuery("select * from trade.portfolio -- GEMFIREXD-PROPERTIES queryHDFS=true \n where cid=1");
    assertTrue(rs.next());
    assertEquals(1,rs.getInt("cid"));
    assertEquals(2,rs.getInt("sid"));
    assertEquals(2000,rs.getInt("qty"));
    assertEquals(200,rs.getInt("availQty"));
    
    // TODO: Verify the size of the region
    LocalRegion lr = (LocalRegion)Misc.getRegion("/TRADE/PORTFOLIO", true, false);
    //assertEquals(0, lr.size());
  }

  @Test
  public void testEvictIncomingWithTrigger() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table e.evictTable_history( id int primary key, "
        + "qty int, abc int )  " + getOffHeapSuffix());
    
    String insertStmt =  "INSERT INTO e.evictTable_history  VALUES (  NEWROW.id ,  NEWROW.qty , NEWROW.abc )";
    String delStmt =  "DELETE FROM e.evictTable_history  WHERE id=OLDROW.id";
    
    String insertTriggerStmt =  "CREATE TRIGGER  e.evictTable_INSERTTRIGGER AFTER INSERT ON e.evictTable REFERENCING NEW AS NEWROW FOR EACH ROW  " + insertStmt ;    
    String deleteTriggerStmt =  "CREATE TRIGGER  e.evictTable_DELETETRIGGER AFTER DELETE ON e.evictTable REFERENCING OLD AS OLDROW  FOR EACH ROW " + delStmt;
    String updateTriggerStmt =  "CREATE TRIGGER  e.evictTable_DELETEFORUPDATE AFTER UPDATE ON e.evictTable REFERENCING NEW AS NEWROW OLD AS OLDROW FOR EACH ROW  " + delStmt;
    String updateTriggerStmt1 = "CREATE TRIGGER  e.evictTable_INSERTFORUPDATE AFTER UPDATE ON e.evictTable REFERENCING NEW AS NEWROW OLD AS OLDROW FOR EACH ROW " + insertStmt;
    
    stmt.execute("create table e.evictTable( id int primary key, qty int, abc int ) "
        + getOffHeapSuffix() + " partition by column(id) "
        + "persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( qty > 100 ) EVICT INCOMING ");
    
    stmt.execute(insertTriggerStmt);
    stmt.execute(deleteTriggerStmt);
    stmt.execute(updateTriggerStmt);
    stmt.execute(updateTriggerStmt1);
    
    stmt.execute("insert into e.evictTable values (1, 1, 200)");
    stmt.execute("insert into e.evictTable values (2, 2, 200)");
    stmt.execute("insert into e.evictTable values (3, 200, 200)");
    stmt.execute("update e.evictTable set qty = 300 where id=3");
    stmt.execute("update e.evictTable set qty = 500 where id=2");
    ResultSet actualRs = stmt.executeQuery("select * from e.evictTable");
    while (actualRs.next()) {
      assertEquals(1,actualRs.getInt("qty"));
      assertEquals(1,actualRs.getInt("id"));
    }
    
      
    ResultSet triggerRs = stmt.executeQuery("select * from e.evictTable_history");
    Set s1 = new HashSet();
    Set s2 = new HashSet();
    while(triggerRs.next()) {
      s1.add(triggerRs.getInt("id"));
      s2.add(triggerRs.getInt("qty"));
    }
    assertEquals(3, s1.size());
    assertEquals(3, s2.size());
    assertTrue(s1.contains(1));
    assertTrue(s1.contains(2));
    assertTrue(s1.contains(3));
    assertTrue(s2.contains(1));
    assertTrue(s2.contains(300));
    assertTrue(s2.contains(500));
    
  } 
  
  /**
   * FK constraint defined on parent table (customers) along with custom eviction criteria.
   * Insert on child table should not throw SQLIntegrityException though parent row is
   * evicted.
   */
  @Test
  public void testEvictIncomingWithForeignKey2() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name int, primary key (cid))  "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( cid > 5 ) EVICT INCOMING ");
    
    stmt.execute("create table trade.networth (netid int not null, "
        + "cid int not null, cash decimal (30, 20), "
        + "constraint netw_pk primary key (netid), "
        + "constraint cust_newt_fk foreign key (cid) references "
        + "trade.customers (cid) on delete restrict) "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5");

    // some inserts
    stmt.executeUpdate("insert into trade.customers values (" + 12 + ", " + (12 * 100) + ")");
    stmt.executeUpdate("insert into trade.networth values (" + 12 + ", "+ 12 + ", " + (12 * 100) + ")");
    
    stmt.executeUpdate("insert into trade.customers values (" + 11 + ", " + (11 * 10) + ")");
    stmt.executeUpdate("insert into trade.networth values (" + 11 + ", "+ 11 + ", " + (11 * 10) + ")");
    try {
      stmt.executeUpdate("delete from trade.customers where cid=11");
      fail("Expected SQLIntegrityConstraintViolationException");
    } catch (SQLException e) {
      if (!"23503".equals(e.getSQLState())) {
        throw e;
      }
    }
    
    stmt.executeUpdate("update trade.customers set cust_name=1100 where cid=11");    
    stmt.executeUpdate("update trade.networth set cash=2000 where cid=11");
    
    ResultSet rs = stmt.executeQuery("select * from trade.customers -- GEMFIREXD-PROPERTIES queryHDFS=true");
    
    while(rs.next()) {
      System.out.println(rs.getInt("cid"));
      System.out.println(rs.getInt("cust_name"));
    }
    System.out.println("END");
  }
  
  /**
   * Eviction already defined on HDFS table.
   * Alter the table to add a foreign key constraint.
   */
  @Test
  public void testEvictIncomingWithAlterTableAddForeignKeyConstraint() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();

    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name int, primary key (cid))  "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( cid > 5 ) EVICT INCOMING ");
    
    stmt.execute("create table trade.networth (netid int not null, cid int not null, cash decimal (30, 20), constraint netw_pk primary key (netid))  " + getOffHeapSuffix() + "  "
        + " persistent hdfsstore (hdfsdata) buckets 5"
        + " eviction by criteria ( cash > 1000 ) EVICT INCOMING ");
    
    try {
      stmt.execute("alter table trade.networth add constraint " +
    	  "cust_newt_fk foreign key (cid) references trade.customers (cid)");
    } catch (SQLFeatureNotSupportedException e) {
      //Expected. Do nothing.
    }
  }
  
  /**
   * No eviction criteria defined on HDFS table. 
   * Alter the table to add a foreign key constraint.
   */
  @Test
  public void testEvictIncomingWithAlterTableAddForeignKeyConstraint_2() throws Exception {
    Connection conn = jdbcConn;
    Statement stmt = conn.createStatement();
    
    stmt.execute("create table trade.customers (cid int not null, "
        + "cust_name int, primary key (cid))  "
        + getOffHeapSuffix() + " persistent hdfsstore (hdfsdata) buckets 5 "
        + "eviction by criteria ( cid > 5 ) EVICT INCOMING ");
    
    stmt.execute("create table trade.networth (netid int not null, "
        + "cid int not null, cash decimal (30, 20), constraint netw_pk "
        + "primary key (netid))  " + getOffHeapSuffix()
        + " persistent hdfsstore (hdfsdata) buckets 5");
    
    stmt.execute("alter table trade.networth add constraint " +
        "cust_newt_fk foreign key (cid) references trade.customers (cid)");

  }

  protected String getOffHeapSuffix() {
    return " ";
  }
}
