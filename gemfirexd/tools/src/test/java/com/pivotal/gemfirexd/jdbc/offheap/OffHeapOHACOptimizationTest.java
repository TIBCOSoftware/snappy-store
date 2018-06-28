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
package com.pivotal.gemfirexd.jdbc.offheap;

import java.lang.reflect.Field;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLWarning;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.Set;
import java.util.SortedMap;
import java.util.SortedSet;
import java.util.TreeMap;
import java.util.TreeSet;

import junit.framework.TestSuite;
import junit.textui.TestRunner;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.cache.BucketRegion;
import com.gemstone.gemfire.internal.cache.CacheObserverHolder;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.OffHeapRegionEntry;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegionDataStore;
import com.gemstone.gemfire.internal.cache.TXState;
import com.gemstone.gemfire.internal.offheap.SimpleMemoryAllocatorImpl.Chunk;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.access.GemFireTransaction;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.store.AbstractCompactExecRow;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.CollectionBasedOHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OHAddressCache;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.internal.iapi.error.StandardException;
import com.pivotal.gemfirexd.internal.iapi.services.io.FormatableBitSet;
import com.pivotal.gemfirexd.internal.iapi.sql.Activation;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.ExecRow;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.NoPutResultSet;
import com.pivotal.gemfirexd.internal.iapi.sql.execute.TargetResultSet;
import com.pivotal.gemfirexd.internal.iapi.types.DataValueDescriptor;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.sql.execute.GenericAggregateResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.execute.GroupedAggregateResultSet;
import com.pivotal.gemfirexd.internal.impl.sql.execute.PlanUtils.Context;
import com.pivotal.gemfirexd.internal.impl.sql.execute.ResultSetStatisticsVisitor;
import com.pivotal.gemfirexd.internal.impl.sql.execute.SortResultSet;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

public class OffHeapOHACOptimizationTest extends JdbcTestBase {
  private RegionMapClearDetector rmcd = null;	
	 	
  public OffHeapOHACOptimizationTest(String name) {
    super(name);
  }

  public static void main(String[] args) {
    TestRunner.run(new TestSuite(OffHeapOHACOptimizationTest.class));
  }

  @Override
  public String reduceLogging() {
    return "config";
  }

  @Override
  public void setUp() throws Exception {
    super.setUp();
    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."
        + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,
        "true");  
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
    rmcd = new JdbcTestBase.RegionMapClearDetector();
    CacheObserverHolder.setInstance(rmcd);
    GemFireXDQueryObserverHolder.putInstance(rmcd);
  }

  @Override
  public void tearDown() throws Exception {
	LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;
	CacheObserverHolder.setInstance(null);
	GemFireXDQueryObserverHolder.clearInstance(); 
    super.tearDown();
    System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
    System.clearProperty("gemfire."
        + DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
    System.clearProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY);
  }
  
  
  ////////////////////////////////order by test///////////////////////////////////////////////
  public void testOrderByUnsortedRowsOptimizedDueToProjection_pr()
      throws Exception {
    basicTestOrderByUnsortedRowsOptimizedDueToProjection("");
  }
  
  public void testOrderByUnsortedRowsOptimizedDueToProjection()
      throws Exception {
    basicTestOrderByUnsortedRowsOptimizedDueToProjection("replicate");
  }
  
  private void basicTestOrderByUnsortedRowsOptimizedDueToProjection(String type)
      throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      // s.execute("create index i3 on trade.buyorders(qty)");

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");
      SortedMap<Integer, Integer> expectedResult = new TreeMap<Integer, Integer>();
      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * i;
        psInsert.setInt(4, qty);
        if (qty < 100 || qty > 150) {
          expectedResult.put(qty, i);
        }
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();
      String query = "select cid, sid from trade.buyorders where  ( qty < 100 or qty > 150) order by qty";
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      Iterator<Integer> values = expectedResult.values().iterator();
      while (rs.next()) {
        assertEquals(values.next().intValue(), rs.getInt(1));

        ++num;
      }
      assertTrue(num > 0);
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  public void testOrderByUnsortedRowsWithNoProjection_pr() throws Exception {
    this.basicTestOrderByUnsortedRowsWithNoProjection(" ");
  }
  
  public void testOrderByUnsortedRowsWithNoProjection_rr() throws Exception {
    this.basicTestOrderByUnsortedRowsWithNoProjection(" replicate");
  }
  
  private void basicTestOrderByUnsortedRowsWithNoProjection(String type) throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      // s.execute("create index i3 on trade.buyorders(qty)");
      SortedMap<Integer, Integer> expectedResult = new TreeMap<Integer, Integer>();

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * i;
        psInsert.setInt(4, qty);
        if (qty < 100 || qty > 150) {
          expectedResult.put(qty, i);
        }
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();
      String query = "select * from trade.buyorders where  ( qty < 100 or qty > 150) order by qty";
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                
               assertTrue(addressCache instanceof CollectionBasedOHAddressCache);
                /*Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);*/

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      Iterator<Integer> values = expectedResult.values().iterator();
      while (rs.next()) {
        assertEquals(values.next().intValue(), rs.getInt(2));

        ++num;
      }
      assertTrue(num > 0);
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  public void testOrderByUnsortedRowsNoIndexNoProjection_pr() throws Exception {
    this.basicTestOrderByUnsortedRowsNoIndexNoProjection(" ");
  }
  
  public void testOrderByUnsortedRowsNoIndexNoProjection_rr() throws Exception {
    this.basicTestOrderByUnsortedRowsNoIndexNoProjection(" replicate");
  }
  
  private void basicTestOrderByUnsortedRowsNoIndexNoProjection(String type) throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      SortedMap<Integer, Integer> expectedResult = new TreeMap<Integer, Integer>();

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * i;
        psInsert.setInt(4, qty);
        if (qty < 100 || qty > 150) {
          expectedResult.put(qty, i);
        }
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();
      String query = "select * from trade.buyorders where  ( qty < 100 or qty > 150) order by qty";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                assertTrue(addressCache instanceof CollectionBasedOHAddressCache);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      Iterator<Integer> values = expectedResult.values().iterator();
      while (rs.next()) {
        assertEquals(values.next().intValue(), rs.getInt(2));

        ++num;
      }
      assertTrue(num > 0);
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();

      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  public void testOrderByUnsortedRowsNoIndexWithProjection_pr() throws Exception {
    this.basicTestOrderByUnsortedRowsNoIndexWithProjection(" ") ;
  }
  
  public void testOrderByUnsortedRowsNoIndexWithProjection_rr() throws Exception {
    this.basicTestOrderByUnsortedRowsNoIndexWithProjection(" replicate ") ;
  }
  private void basicTestOrderByUnsortedRowsNoIndexWithProjection(String type) throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      SortedMap<Integer, Integer> expectedResult = new TreeMap<Integer, Integer>();

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * i;
        psInsert.setInt(4, qty);
        if (qty < 100 || qty > 150) {
          expectedResult.put(qty, i);
        }
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();

      String query = "select cid, sid from trade.buyorders where  ( qty < 100 or qty > 150) order by qty";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                 assertEquals(addressCacheClass,addressCache.getClass() );

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      Iterator<Integer> values = expectedResult.values().iterator();
      while (rs.next()) {
        assertEquals(values.next().intValue(), rs.getInt(1));
        rs.getInt(2);
        ++num;
      }
      assertTrue(num > 0);
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testOrderBySortedRowsIndexWithNoProjection_pr() throws Exception { 
    this.basicTestOrderBySortedRowsIndexWithNoProjection(" ") ;
  }
  
  public void testOrderBySortedRowsIndexWithNoProjection_rr() throws Exception { 
    this.basicTestOrderBySortedRowsIndexWithNoProjection(" replicate ") ;
  }
  
  public void basicTestOrderBySortedRowsIndexWithNoProjection(String type) throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");
      SortedMap<Integer, Integer> expectedResult = new TreeMap<Integer, Integer>();

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * i;
        psInsert.setInt(4, qty);
        expectedResult.put(qty, i);
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();
      String query = "select * from trade.buyorders  order by qty";
      ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
      
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCacheClass,addressCache.getClass() );

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      GemFireXDQueryObserverHolder.putInstance(observer);
      observer.addExpectedScanType("trade.buyorders", "trade.i3", ScanType.SORTEDMAPINDEX);
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      Iterator<Integer> values = expectedResult.values().iterator();
      while (rs.next()) {
        assertEquals(values.next().intValue(), rs.getInt(2));

        ++num;
      }
      assertTrue(num > 0);
      rs.close();
      observer.checkAndClear();
      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }
      
      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testOrderByUnSortedRowsNoProjection_pr() throws Exception {
    basicTestOrderByUnSortedRowsNoProjection(" ") ;
  }
  
  public void testOrderByUnSortedRowsNoProjection_rr() throws Exception {
    basicTestOrderByUnSortedRowsNoProjection(" replicate") ;
  }
  
  private  void basicTestOrderByUnSortedRowsNoProjection(String type) throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
     
      SortedMap<Integer, Integer> expectedResult = new TreeMap<Integer, Integer>();

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * i;
        psInsert.setInt(4, qty);
        expectedResult.put(qty, i);
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();
      String query = "select * from trade.buyorders  order by qty";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                assertTrue(addressCache instanceof CollectionBasedOHAddressCache );

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      Iterator<Integer> values = expectedResult.values().iterator();
      while (rs.next()) {
        assertEquals(values.next().intValue(), rs.getInt(2));

        ++num;
      }
      assertTrue(num > 0);
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testOrderByUnSortedRowsIndexWithNoProjection_pr() throws Exception {
    basicTestOrderByUnSortedRowsIndexWithNoProjection( " ") ;
  }
  
  public void testOrderByUnSortedRowsIndexWithNoProjection_rr() throws Exception {
    basicTestOrderByUnSortedRowsIndexWithNoProjection( " replicate") ;
  }
  
  private void basicTestOrderByUnSortedRowsIndexWithNoProjection(String type) throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");
      
      
      NavigableMap<Integer, Integer> expectedResult = new TreeMap<Integer, Integer>();

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * i;
        psInsert.setInt(4, qty);
        expectedResult.put(qty, i);
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();
      String query = "select * from trade.buyorders  order by qty desc";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                assertTrue(addressCache instanceof CollectionBasedOHAddressCache );

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }
          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      Iterator<Integer> values = expectedResult.descendingMap().values().iterator();
      while (rs.next()) {
        assertEquals(values.next().intValue(), rs.getInt(2));

        ++num;
      }
      assertTrue(num > 0);
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  //////////////////////////////////////////Distinct test /////////////////////////////////
  
  public void testDistinctUnsortedRowsOptimizedDueToProjection_pr() 
      throws Exception {
    this.basicTestDistinctUnsortedRowsOptimizedDueToProjection("");
  }
  
  public void testDistinctUnsortedRowsOptimizedDueToProjection_rr() 
      throws Exception {
    this.basicTestDistinctUnsortedRowsOptimizedDueToProjection("replicate");
  }
  
  private void basicTestDistinctUnsortedRowsOptimizedDueToProjection(String type)
      throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      // s.execute("create index i3 on trade.buyorders(qty)");

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");
      SortedSet<Integer> expectedResult = new TreeSet<Integer>();
      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * (i%5);
        psInsert.setInt(4, qty);
        if (i>10) {
          expectedResult.add(qty);
        }
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();

      String query = "select distinct qty from trade.buyorders where  cid > 10 ";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) tableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      //Iterator<Integer> values = expectedResult.iterator();
      while (rs.next()) {
        assertTrue(expectedResult.remove(rs.getInt(1)));
        ++num;
      }
      assertTrue(num > 0);
      assertTrue(expectedResult.isEmpty());
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testDistinctUnsortedRowsWithNoProjection_pr()
      throws Exception {
    this.basicTestDistinctUnsortedRowsWithNoProjection("");
  }
  
  public void testDistinctUnsortedRowsWithNoProjection_rr()
      throws Exception {
    this.basicTestDistinctUnsortedRowsWithNoProjection("replicate ");
  }
  
  private void basicTestDistinctUnsortedRowsWithNoProjection(String type)
      throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null ,"
          + " cid int, sid int, qty int, bid decimal (30, 20),  status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      // s.execute("create index i3 on trade.buyorders(qty)");

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?)");
      SortedSet<String> expectedResult = new TreeSet<String>();
      for (int i = 1; i < 500; ++i) {
        StringBuilder builder = new StringBuilder();
        psInsert.setInt(1, i%20);
        builder.append(i%20).append(',');
        psInsert.setInt(2, i%20);
        builder.append(i%20).append(',');
        psInsert.setInt(3, -1 * i%20);
        builder.append(-1*(i%20)).append(',');
        int qty = 100 * (i%5);
        psInsert.setInt(4, qty);
        builder.append(qty).append(',');
        psInsert.setFloat(5, 30.40f);
        builder.append(30.40).append(',');
        psInsert.setString(6, "cancelled");
        builder.append("cancelled").append(',');
        psInsert.setInt(7, 5);
        builder.append(5);
        assertEquals(1, psInsert.executeUpdate());
        expectedResult.add(builder.toString());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();
      
      String query = "select distinct * from trade.buyorders where  cid >= 0 ";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                assertTrue(addressCache instanceof CollectionBasedOHAddressCache);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onSortResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                SortResultSet srs = (SortResultSet) resultSet;
                NoPutResultSet source = srs.getSource();
                source = conditionSourceResultSet(source);
                Class<?> clazz = SortResultSet.class;
                Field field = clazz.getDeclaredField("source");
                field.setAccessible(true);
                field.set(srs, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      //Iterator<Integer> values = expectedResult.iterator();
      while (rs.next()) {
       StringBuilder sb = new StringBuilder();
       sb.append(rs.getInt(1)).append(',').append(rs.getInt(2)).append(',')
       .append(rs.getInt(3)).append(',').append(rs.getInt(4)).append(',')
       .append(rs.getFloat(5)).append(',').append(rs.getString(6)).append(',')
       .append(rs.getInt(7));
       assertTrue(expectedResult.remove(sb.toString()));
        ++num;
      }
      assertTrue(num > 0);
      assertTrue(expectedResult.isEmpty());
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  ////////////////////////////// Group By Test///////////////////////////////////////////////
  
  public void testGroupByUnsortedRowsOptimizedDueToProjection_pr()  throws Exception {
    basicTestGroupByUnsortedRowsOptimizedDueToProjection( " " );
  }
  
  public void testGroupByUnsortedRowsOptimizedDueToProjection_rr()  throws Exception {
    basicTestGroupByUnsortedRowsOptimizedDueToProjection( " replicate" );
  }
  
  public void basicTestGroupByUnsortedRowsOptimizedDueToProjection(String type)
      throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap "+type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      //s.execute("create index i2 on trade.buyorders(cid, sid)");
      // s.execute("create index i3 on trade.buyorders(qty)");

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");
      SortedSet<Integer> expectedResult = new TreeSet<Integer>();
      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * (i%5);
        psInsert.setInt(4, qty);
        if (i>10) {
          expectedResult.add(qty);
        }
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();

      String query = "select  qty from trade.buyorders where  cid > 10 group by qty ";
      // Check for TableScan.SingleFieldOHAddressCache as  index access is faster
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onGroupedAggregateResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                GroupedAggregateResultSet gars = (GroupedAggregateResultSet) resultSet;
                Class<?> genericAggClass = GenericAggregateResultSet.class;
                Field sourceField = genericAggClass.getDeclaredField("source");
                sourceField.setAccessible(true);
                
                NoPutResultSet source = (NoPutResultSet) sourceField.get(gars);
                source = conditionSourceResultSet(source);                
                sourceField.set(gars, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      //Iterator<Integer> values = expectedResult.iterator();
      while (rs.next()) {
        assertTrue(expectedResult.remove(rs.getInt(1)));
        ++num;
      }
      assertTrue(num > 0);
      assertTrue(expectedResult.isEmpty());
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testGroupByWithAggregateFunction_pr_1()
      throws Exception {
    basicTestGroupByWithAggregateFunction_1(" ");
  }
  
  public void testGroupByWithAggregateFunction_rr_1()
      throws Exception {
    basicTestGroupByWithAggregateFunction_1(" replicate");
  }
  private void basicTestGroupByWithAggregateFunction_1(String type)
      throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      //s.execute("create index i2 on trade.buyorders(cid, sid)");
      // s.execute("create index i3 on trade.buyorders(qty)");

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");
      Map<Integer, Integer> expectedResult = new HashMap<Integer, Integer>();
      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * (i%5);
        psInsert.setInt(4, qty);
        if (i>10) {
          int val = -1 *i;
          Integer oldVal = expectedResult.get(qty);
          if(oldVal != null) {
            val += oldVal;
          }
          expectedResult.put(qty, val);
        }
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();

      String query = "select  sum(sid) from trade.buyorders where  cid > 10 group by qty ";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onGroupedAggregateResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                GroupedAggregateResultSet gars = (GroupedAggregateResultSet) resultSet;
                Class<?> genericAggClass = GenericAggregateResultSet.class;
                Field sourceField = genericAggClass.getDeclaredField("source");
                sourceField.setAccessible(true);
                
                NoPutResultSet source = (NoPutResultSet) sourceField.get(gars);
                source = conditionSourceResultSet(source);                
                sourceField.set(gars, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      //Iterator<Integer> values = expectedResult.iterator();
      Collection<Integer> vals = expectedResult.values();
      while (rs.next()) {
        assertTrue(vals.remove(rs.getInt(1)));
        ++num;
      }
      assertTrue(num > 0);
      assertTrue(vals.isEmpty());
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testGroupByOnSortedData_pr()
      throws Exception {
    basicTestGroupByOnSortedData(" ");
  }
  
  public void testGroupByOnSortedData_rr()
      throws Exception {
    basicTestGroupByOnSortedData(" replicate");
  }
  private void basicTestGroupByOnSortedData(String type)
      throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
       s.execute("create index i3 on trade.buyorders(qty)");

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");
      Map<Integer, Integer> expectedResult = new HashMap<Integer, Integer>();
      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * (i%5);
        psInsert.setInt(4, qty);
          int val = -1 *i;
          Integer oldVal = expectedResult.get(qty);
          if(oldVal != null) {
            val += oldVal;
          }
          expectedResult.put(qty, val);
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();

      String query = "select  sum(sid) from trade.buyorders  group by qty ";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onGroupedAggregateResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                GroupedAggregateResultSet gars = (GroupedAggregateResultSet) resultSet;
                Class<?> genericAggClass = GenericAggregateResultSet.class;
                Field sourceField = genericAggClass.getDeclaredField("source");
                sourceField.setAccessible(true);
                
                NoPutResultSet source = (NoPutResultSet) sourceField.get(gars);
                source = conditionSourceResultSet(source);                
                sourceField.set(gars, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

          });
      ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
      GemFireXDQueryObserverHolder.putInstance(observer);
      observer.addExpectedScanType("trade.buyorders", "trade.i3", ScanType.SORTEDMAPINDEX);
     
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      //Iterator<Integer> values = expectedResult.iterator();
      Collection<Integer> vals = expectedResult.values();
      while (rs.next()) {
        assertTrue(vals.remove(rs.getInt(1)));
        ++num;
      }
      assertTrue(num > 0);
      assertTrue(vals.isEmpty());
      rs.close();
      observer.checkAndClear();
      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testGroupByOptimizedWithSortedRows_pr()
      throws Exception {
    basicTestGroupByOptimizedWithSortedRows(" ");
  }
  
  public void testGroupByOptimizedWithSortedRows_rr()
      throws Exception {
    basicTestGroupByOptimizedWithSortedRows(" replicate");
  }
  private void basicTestGroupByOptimizedWithSortedRows(String type)
      throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");
      Set<Integer> expectedResult = new HashSet<Integer>();
      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * (i%5);
        psInsert.setInt(4, qty);
        expectedResult.add(qty);
        
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();

      String query = "select qty from trade.buyorders  group by qty ";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                assertNull(addressCache);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onGroupedAggregateResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                GroupedAggregateResultSet gars = (GroupedAggregateResultSet) resultSet;
                Class<?> genericAggClass = GenericAggregateResultSet.class;
                Field sourceField = genericAggClass.getDeclaredField("source");
                sourceField.setAccessible(true);
                
                NoPutResultSet source = (NoPutResultSet) sourceField.get(gars);
                source = conditionSourceResultSet(source);                
                sourceField.set(gars, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

          });
      ScanTypeQueryObserver observer = new ScanTypeQueryObserver();
      GemFireXDQueryObserverHolder.putInstance(observer);
      observer.addExpectedScanType("trade.buyorders", "trade.i3", ScanType.SORTEDMAPINDEX);
     
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      //Iterator<Integer> values = expectedResult.iterator();
      while (rs.next()) {
        assertTrue(expectedResult.remove(rs.getInt(1)));
        ++num;
      }
      assertTrue(num > 0);
      assertTrue(expectedResult.isEmpty());
      rs.close();
      observer.checkAndClear();
      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  public void testGroupByWithAggregateFunction_pr_2()
      throws Exception {
    basicTestGroupByWithAggregateFunction_2(" ");
  }
  
  public void testGroupByWithAggregateFunction_rr_2()
      throws Exception {
    basicTestGroupByWithAggregateFunction_2(" replicate");
  }
  private void basicTestGroupByWithAggregateFunction_2(String type)
      throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap " + type;

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      //s.execute("create index i2 on trade.buyorders(cid, sid)");
      // s.execute("create index i3 on trade.buyorders(qty)");

      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");
      Map<Integer, Integer> expectedResult = new HashMap<Integer, Integer>();
      for (int i = 1; i < 500; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        int qty = 100 * (i%5);
        psInsert.setInt(4, qty);
        if (i>10) {
          int val = 1;
          Integer oldVal = expectedResult.get(qty);
          if(oldVal != null) {
            val += oldVal;
          }
          expectedResult.put(qty, val);
        }
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }

      List<OffHeapRegionEntry> allEntries = collectAllEntries();

      String query = "select distinct count(*) from trade.buyorders where  cid > 10 group by qty ";
      // Check for TableScan.SingleFieldOHAddressCache as it is a index query
      GemFireXDQueryObserverHolder
          .putInstance(new GemFireXDQueryObserverAdapter() {
            @Override
            public void onGetNextRowCoreOfBulkTableScan(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                Class<?> bulkTableScanClazz = resultSet.getClass();
                Class<?> tableScanClazz = resultSet.getClass().getSuperclass();
                Field field = tableScanClazz.getDeclaredField("ohAddressCache");
                field.setAccessible(true);
                OHAddressCache addressCache = (OHAddressCache) field
                    .get(resultSet);
                Class<? extends OHAddressCache> addressCacheClass = (Class<? extends OHAddressCache>) bulkTableScanClazz
                    .getDeclaredClasses()[0];
                assertEquals(addressCache.getClass(), addressCacheClass);

              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

            @Override
            public void onGroupedAggregateResultSetOpen(
                com.pivotal.gemfirexd.internal.iapi.sql.ResultSet resultSet) {
              try {
                GroupedAggregateResultSet gars = (GroupedAggregateResultSet) resultSet;
                Class<?> genericAggClass = GenericAggregateResultSet.class;
                Field sourceField = genericAggClass.getDeclaredField("source");
                sourceField.setAccessible(true);
                
                NoPutResultSet source = (NoPutResultSet) sourceField.get(gars);
                source = conditionSourceResultSet(source);                
                sourceField.set(gars, source);
              } catch (Exception e) {
                throw new RuntimeException(e);
              }

            }

          });
      ResultSet rs = s.executeQuery(query);
      int num = 0;
      //Iterator<Integer> values = expectedResult.iterator();
      Collection<Integer> vals = expectedResult.values();
      Set<Integer> valss = new HashSet<Integer>();
      valss.addAll(vals);
      while (rs.next()) {
        assertTrue(valss.remove(rs.getInt(1)));
        ++num;
      }
      assertTrue(num > 0);
      assertTrue(valss.isEmpty());
      rs.close();

      // since the ref counts of some entries will be 0 by now, we need to
      // handle those
      for (OffHeapRegionEntry ohre : allEntries) {
        long address = ohre.getAddress();
        if (Chunk.retain(address)) {
          Chunk.release(address, true);
        } else {
          ohre.setAddress(address, 0);
        }
      }

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        rmcd.waitTillAllClear();
      }

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  private List<OffHeapRegionEntry> collectAllEntries() {
    List<OffHeapRegionEntry> allEntries = new ArrayList<OffHeapRegionEntry>();
    LocalRegion lr = Misc.getRegionByPath("/TRADE/BUYORDERS");
    if(lr instanceof PartitionedRegion) {
      PartitionedRegion pr = (PartitionedRegion)lr;
      PartitionedRegionDataStore prs = pr.getDataStore();
      if (prs != null) {
        Set<BucketRegion> brs = prs.getAllLocalBucketRegions();
        if (brs != null) {
          for (BucketRegion br : brs) {
            if (br != null) {
              this.collectAllEntries(br, allEntries);
            }
          }
        }
      }    
    }else {
      this.collectAllEntries(lr, allEntries);  
    }
    return allEntries;
  }
  
  private void collectAllEntries(LocalRegion rgn, List<OffHeapRegionEntry> allEntries) {
    Set<Object> keys = rgn.keys();
    for (Object key : keys) {
      OffHeapRegionEntry ohre = (OffHeapRegionEntry) rgn.basicGetEntry(key);
      allEntries.add(ohre);
    }
    
  }

  private NoPutResultSet conditionSourceResultSet(
      final NoPutResultSet noputResultSet) {

    NoPutResultSet testResultSetPassThru = new NoPutResultSet() {

      @Override
      public void deleteRowDirectly() throws StandardException {
        noputResultSet.deleteRowDirectly();
      }

      @Override
      public boolean canUpdateInPlace() {
        return noputResultSet.canUpdateInPlace();
      }

      @Override
      public boolean needsToClone() {
        return noputResultSet.needsToClone();
      }

      @Override
      public FormatableBitSet getValidColumns() {
        return noputResultSet.getValidColumns();
      }

      @Override
      public ExecRow getNextRowFromRowSource() throws StandardException {
        return noputResultSet.getNextRowFromRowSource();
      }

      @Override
      public void closeRowSource() {
        noputResultSet.closeRowSource();

      }

      @Override
      public void rowLocation(RowLocation rl) throws StandardException {
        noputResultSet.rowLocation(rl);

      }

      @Override
      public boolean needsRowLocation() {
        return noputResultSet.needsRowLocation();
      }

      @Override
      public ExecRow setBeforeFirstRow() throws StandardException {
        return noputResultSet.setBeforeFirstRow();
      }

      @Override
      public ExecRow setAfterLastRow() throws StandardException {
        return noputResultSet.setAfterLastRow();
      }

      @Override
      public boolean returnsRows() {
        return noputResultSet.returnsRows();
      }

      @Override
      public void resetStatistics() {
        noputResultSet.resetStatistics();
      }

      @Override
      public boolean releaseLocks(GemFireTransaction tran) {
        return noputResultSet.releaseLocks(tran);
      }

      @Override
      public void open() throws StandardException {
        noputResultSet.open();
      }

      @Override
      public int modifiedRowCount() {
        // TODO Auto-generated method stub
        return noputResultSet.modifiedRowCount();
      }

      @Override
      public void markLocallyExecuted() {
        noputResultSet.markLocallyExecuted();

      }

      @Override
      public boolean isDistributedResultSet() {
        return noputResultSet.isDistributedResultSet();

      }

      @Override
      public boolean isClosed() {
        return noputResultSet.isClosed();
      }

      @Override
      public boolean hasAutoGeneratedKeysResultSet() {
        return noputResultSet.hasAutoGeneratedKeysResultSet();
      }

      @Override
      public SQLWarning getWarnings() {
        return noputResultSet.getWarnings();
      }

      @Override
      public long getTimeSpent(int type, int timeType) {
        return noputResultSet.getTimeSpent(type, timeType);
      }

      @Override
      public NoPutResultSet[] getSubqueryTrackingArray(int numSubqueries) {
        return noputResultSet.getSubqueryTrackingArray(numSubqueries);
      }

      @Override
      public int getRowNumber() {
        // TODO Auto-generated method stub
        return noputResultSet.getRowNumber();
      }

      @Override
      public ExecRow getRelativeRow(int row) throws StandardException {
        return noputResultSet.getRelativeRow(row);
      }

      @Override
      public ExecRow getPreviousRow() throws StandardException {
        return noputResultSet.getPreviousRow();
      }

      @Override
      public ExecRow getNextRow() throws StandardException {
        return noputResultSet.getNextRow();
      }

      @Override
      public ExecRow getLastRow() throws StandardException {
        return noputResultSet.getLastRow();
      }

      @Override
      public ExecRow getFirstRow() throws StandardException {
        return noputResultSet.getFirstRow();
      }

      @Override
      public com.pivotal.gemfirexd.internal.catalog.UUID getExecutionPlanID() {
        return noputResultSet.getExecutionPlanID();
      }

      @Override
      public long getExecuteTime() {
        return noputResultSet.getExecuteTime();
      }

      @Override
      public Timestamp getEndExecutionTimestamp() {
        // TODO Auto-generated method stub
        return noputResultSet.getEndExecutionTimestamp();
      }

      @Override
      public String getCursorName() {
        // TODO Auto-generated method stub
        return noputResultSet.getCursorName();
      }

      @Override
      public Timestamp getBeginExecutionTimestamp() {
        // TODO Auto-generated method stub
        return noputResultSet.getBeginExecutionTimestamp();
      }

      @Override
      public com.pivotal.gemfirexd.internal.iapi.sql.ResultSet getAutoGeneratedKeysResultset() {
        // TODO Auto-generated method stub
        return noputResultSet.getAutoGeneratedKeysResultset();
      }

      @Override
      public Activation getActivation() {
        // TODO Auto-generated method stub
        return noputResultSet.getActivation();
      }

      @Override
      public ExecRow getAbsoluteRow(int row) throws StandardException {
        // TODO Auto-generated method stub
        return noputResultSet.getAbsoluteRow(row);
      }

      @Override
      public void flushBatch() throws StandardException {
        // TODO Auto-generated method stub
        noputResultSet.flushBatch();

      }

      @Override
      public void finish() throws StandardException {
        // TODO Auto-generated method stub
        noputResultSet.finish();
      }

      @Override
      public void closeBatch() throws StandardException {
        noputResultSet.closeBatch();

      }

      @Override
      public void close(boolean cleanupOnError) throws StandardException {
        noputResultSet.close(cleanupOnError);
      }

      @Override
      public void clearCurrentRow() {
        noputResultSet.clearCurrentRow();
      }

      @Override
      public void cleanUp(boolean cleanupOnError) throws StandardException {
        noputResultSet.cleanUp(cleanupOnError);
      }

      @Override
      public boolean checkRowPosition(int isType) throws StandardException {
        return noputResultSet.checkRowPosition(isType);
      }

      @Override
      public void checkCancellationFlag() throws StandardException {
        noputResultSet.checkCancellationFlag();
      }

      @Override
      public boolean addLockReference(GemFireTransaction tran) {
        // TODO Auto-generated method stub
        return noputResultSet.addLockReference(tran);
      }

      @Override
      public void accept(ResultSetStatisticsVisitor visitor) {
        noputResultSet.accept(visitor);

      }

      @Override
      public void upgradeReadLockToWrite(RowLocation rl,
          GemFireContainer container) throws StandardException {
        noputResultSet.upgradeReadLockToWrite(rl, container);

      }

      @Override
      public void updateRowLocationPostRead() throws StandardException {
        noputResultSet.updateRowLocationPostRead();
      }

      @Override
      public void updateRow(ExecRow row) throws StandardException {
        noputResultSet.updateRow(row);
      }

      @Override
      public boolean supportsMoveToNextKey() {
        return noputResultSet.supportsMoveToNextKey();
      }

      @Override
      public void setTargetResultSet(TargetResultSet trs) {
        noputResultSet.setTargetResultSet(trs);
      }

      @Override
      public void setNeedsRowLocation(boolean needsRowLocation) {
        noputResultSet.setNeedsRowLocation(needsRowLocation);
      }

      @Override
      public void setGfKeysForNCJoin(ArrayList<DataValueDescriptor> keys)
          throws StandardException {
        noputResultSet.setGfKeysForNCJoin(keys);

      }

      @Override
      public void setCurrentRow(ExecRow row) {
        noputResultSet.setCurrentRow(row);
      }

      @Override
      public int resultSetNumber() {
        // TODO Auto-generated method stub
        return noputResultSet.resultSetNumber();
      }

      @Override
      public boolean requiresRelocking() {
        // TODO Auto-generated method stub
        return noputResultSet.requiresRelocking();
      }

      @Override
      public void reopenCore() throws StandardException {
        noputResultSet.reopenCore();
      }

      @Override
      public void releasePreviousByteSource() {
        noputResultSet.releasePreviousByteSource();
      }

      @Override
      public void setMaxSortingLimit(long limit) {
        noputResultSet.setMaxSortingLimit(limit);
      }

      @Override
      public void positionScanAtRowLocation(RowLocation rLoc)
          throws StandardException {
        noputResultSet.positionScanAtRowLocation(rLoc);

      }

      @Override
      public void openCore() throws StandardException {
        noputResultSet.openCore();

      }

      @Override
      public void markRowAsDeleted() throws StandardException {
        noputResultSet.markRowAsDeleted();
      }

      @Override
      public void markAsTopResultSet() {
        noputResultSet.markAsTopResultSet();
      }

      @Override
      public boolean isForUpdate() {
        // TODO Auto-generated method stub
        return noputResultSet.isForUpdate();
      }

      @Override
      public TXState initLocalTXState() {
        // TODO Auto-generated method stub
        return noputResultSet.initLocalTXState();
      }

      @Override
      public int getScanKeyGroupID() {
        // TODO Auto-generated method stub
        return noputResultSet.getScanKeyGroupID();
      }

      @Override
      public int getScanIsolationLevel() {
        // TODO Auto-generated method stub
        return noputResultSet.getScanIsolationLevel();
      }

      @Override
      public int getPointOfAttachment() {
        // TODO Auto-generated method stub
        return noputResultSet.getPointOfAttachment();
      }

      private List<RowLocation> rowsScanned = new ArrayList<RowLocation>();

      @Override
      public ExecRow getNextRowCore() throws StandardException {

        ExecRow row = noputResultSet.getNextRowCore();
        if (row != null) {
          if (row instanceof AbstractCompactExecRow) {
            Object bs = ((AbstractCompactExecRow) row).getByteSource();
            if (bs instanceof OffHeapByteSource) {
              ((OffHeapByteSource) bs).release();
            }
          }
        }
        return row;
      }

      @Override
      public Context getNewPlanContext() {
        // TODO Auto-generated method stub
        return noputResultSet.getNewPlanContext();
      }

      @Override
      public double getEstimatedRowCount() {
        // TODO Auto-generated method stub
        return noputResultSet.getEstimatedRowCount();
      }

      @Override
      public void filteredRowLocationPostRead(TXState localTXState)
          throws StandardException {
        noputResultSet.filteredRowLocationPostRead(localTXState);

      }

      @Override
      public RowLocation fetch(RowLocation loc, ExecRow destRow,
          FormatableBitSet validColumns, boolean faultIn,
          GemFireContainer container) throws StandardException {
        // TODO Auto-generated method stub
        return noputResultSet.fetch(loc, destRow, validColumns, faultIn,
            container);
      }

      @Override
      public StringBuilder buildQueryPlan(StringBuilder builder, Context context) {
        // TODO Auto-generated method stub
        return noputResultSet.buildQueryPlan(builder, context);
      }
      
      @Override
      public void forceReOpenCore() throws StandardException { 
        noputResultSet.forceReOpenCore();
      }
    };
    return testResultSetPassThru;
  }

}
