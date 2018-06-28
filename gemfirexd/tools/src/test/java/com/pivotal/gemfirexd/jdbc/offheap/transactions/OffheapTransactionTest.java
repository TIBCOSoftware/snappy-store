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
package com.pivotal.gemfirexd.jdbc.offheap.transactions;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.util.Iterator;

import com.gemstone.gemfire.distributed.internal.DistributionConfig;
import com.gemstone.gemfire.internal.cache.CacheObserverHolder;
import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.concurrent.ConcurrentSkipListMap;
import com.gemstone.gemfire.internal.offheap.annotations.Released;
import com.gemstone.gemfire.internal.offheap.annotations.Retained;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverAdapter;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.engine.access.index.OpenMemIndex;
import com.pivotal.gemfirexd.internal.engine.distributed.metadata.SelectQueryInfo;
import com.pivotal.gemfirexd.internal.engine.jdbc.GemFireXDRuntimeException;
import com.pivotal.gemfirexd.internal.engine.management.GfxdManagementService;
import com.pivotal.gemfirexd.internal.engine.store.CompactCompositeIndexKey;
import com.pivotal.gemfirexd.internal.engine.store.GemFireContainer;
import com.pivotal.gemfirexd.internal.engine.store.offheap.OffHeapByteSource;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase.RegionMapClearDetector;
import com.pivotal.gemfirexd.jdbc.transactions.TransactionTest;

public class OffheapTransactionTest extends TransactionTest {
  
  private RegionMapClearDetector rmcd = null;
  public OffheapTransactionTest(String name) {
    super(name);    
  }
  
  public void setUp() throws Exception {
    super.setUp();
    System.setProperty("gemfire.OFF_HEAP_TOTAL_SIZE", "500m");
    System.setProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME, "500m");
    System.setProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY,"true");
    
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = true;
    rmcd = new JdbcTestBase.RegionMapClearDetector();
    CacheObserverHolder.setInstance(rmcd);
    GemFireXDQueryObserverHolder.setInstance(rmcd);
  }
  
  public void testTransactionalIndexKeyBehaviour_1() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);

    PreparedStatement psInsert1, psInsert2, psInsert3, psInsert4 = null;
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");

      conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);

      psInsert2 = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 3; ++i) {
        psInsert2.setInt(1, i);
        psInsert2.setInt(2, i);
        psInsert2.setInt(3, -1 * i);
        psInsert2.setInt(4, 100 * i);
        psInsert2.setFloat(5, 30.40f);
        psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert2.setString(7, "cancelled");
        psInsert2.setInt(8, 5);
        assertEquals(1, psInsert2.executeUpdate());
      }
      conn.commit();
      final GemFireContainer[] indexContainerI1 = new GemFireContainer[1];
      // At this point newly created entries of the index should have index key
      // as
      // offheap byte source
      // Check one of the indexes for index key being value byte source
      GemFireXDQueryObserverHolder.putInstance(new GemFireXDQueryObserverAdapter() {
        @Override
        public double overrideDerbyOptimizerIndexUsageCostForSortedIndexScan(
            OpenMemIndex memIndex, double optimzerEvalutatedCost) {
          // if(memIndex.getGemFireContainer().getName().toString().indexOf("TRADE.I1")
          // != -1) {
          indexContainerI1[0] = memIndex.getGemFireContainer();
          // }
          return optimzerEvalutatedCost;
        }
      });

      Statement st = conn.createStatement();
      st.executeQuery("select * from trade.buyorders where status = 'cancelled'");
      assertNotNull(indexContainerI1[0]);
      ConcurrentSkipListMap<Object, Object> indexMap = indexContainerI1[0]
          .getSkipListMap();
      Iterator<Object> indexKeys = indexMap.keySet().iterator();
      int num = 0;
      while (indexKeys.hasNext()) {
        CompactCompositeIndexKey ccik = (CompactCompositeIndexKey) indexKeys
            .next();
        @Retained @Released Object valueByteSource = ccik.getValueByteSource();
        assertTrue(valueByteSource instanceof OffHeapByteSource);
        ccik.releaseValueByteSource(valueByteSource);

        ++num;
      }
      assertEquals(2, num);

      st.executeUpdate("update trade.buyorders set status = 'xxxx' where cid =1");
      st.executeUpdate("update trade.buyorders set status = 'cancelled' where cid = 1");

      conn.commit();

      psInsert2.setInt(1, 4);
      psInsert2.setInt(2, 4);
      psInsert2.setInt(3, -1 * 4);
      psInsert2.setInt(4, 100 * 4);
      psInsert2.setFloat(5, 30.40f);
      psInsert2.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert2.setString(7, "cancelled");
      psInsert2.setInt(8, 5);
      assertEquals(1, psInsert2.executeUpdate());
      this.doOffHeapValidations();
      conn.commit();
      s.executeUpdate("delete from trade.buyorders");
      conn.commit();
      this.doOffHeapValidations();
      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        this.waitTillAllClear();

      }

    } finally {
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }

  public void testTransactionalIndexKeyBehaviour_2() throws Exception {
    SelectQueryInfo.setTestFlagIgnoreSingleVMCriteria(true);
    Statement s = null;

    Connection conn = TestUtil.getConnection();

    s = conn.createStatement();
    try {
      // Creating a statement object that we can use for running various
      // SQL statements commands against the database.

      String tab3 = " create table trade.buyorders(oid int not null constraint buyorders_pk primary key,"
          + " cid int, sid int, qty int, bid decimal (30, 20), ordertime timestamp, status varchar(10),"
          + " tid int, constraint bo_qty_ck check (qty>=0))  offheap";

      String exchange[] = new String[] { "nasdaq", "nye", "amex", "lse", "fse",
          "hkse", "tse" };
      // We create a table...

      s.execute(tab3);
      s.execute("create index i1 on trade.buyorders(status)");
      s.execute("create index i2 on trade.buyorders(cid, sid)");
      s.execute("create index i3 on trade.buyorders(qty)");

      conn.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
      conn.setAutoCommit(false);
      PreparedStatement psInsert = conn
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");

      for (int i = 1; i < 3; ++i) {
        psInsert.setInt(1, i);
        psInsert.setInt(2, i);
        psInsert.setInt(3, -1 * i);
        psInsert.setInt(4, 100 * i);
        psInsert.setFloat(5, 30.40f);
        psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
        psInsert.setString(7, "cancelled");
        psInsert.setInt(8, 5);
        assertEquals(1, psInsert.executeUpdate());
      }
      conn.commit();
      final Connection conn1 = TestUtil.getConnection();
      final PreparedStatement psInsert1 = conn1
          .prepareStatement("insert into trade.buyorders values (?, ?,?,?,?,?,?,?)");
      final Statement s1 = conn1.createStatement();
      Statement st = conn.createStatement();
      st.executeUpdate("update trade.buyorders set status = 'xxxx' where cid =1");

      Thread th = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            psInsert1.setInt(1, 4);
            psInsert1.setInt(2, 4);
            psInsert1.setInt(3, -1 * 4);
            psInsert1.setInt(4, 100 * 4);
            psInsert1.setFloat(5, 40.40f);
            psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
            psInsert1.setString(7, "xxxx");
            psInsert1.setInt(8, 5);
            psInsert1.executeUpdate();
          } catch (Exception e) {
            e.printStackTrace();
            fail(e.toString());
          }

        }
      });
      th.start();
      th.join();
      conn.rollback();
      psInsert1.setInt(1, 5);
      psInsert1.setInt(2, 5);
      psInsert1.setInt(3, -1 * 5);
      psInsert1.setInt(4, 100 * 5);
      psInsert1.setFloat(5, 40.40f);
      psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert1.setString(7, "xxxx");
      psInsert1.setInt(8, 5);
      psInsert1.executeUpdate();
      ResultSet rs1 = s1.executeQuery("select * from trade.buyorders where "
          + "status = 'xxxx'");
      int num = 0;
      while (rs1.next()) {
        assertEquals("xxxx", rs1.getString("status"));
        ++num;
      }
      assertEquals(2, num);
      psInsert1.setInt(1, 6);
      psInsert1.setInt(2, 6);
      psInsert1.setInt(3, -1 * 6);
      psInsert1.setInt(4, 100 * 6);
      psInsert1.setFloat(5, 40.40f);
      psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert1.setString(7, "yyyy");
      psInsert1.setInt(8, 5);
      psInsert1.executeUpdate();
      s.executeUpdate("delete from trade.buyorders where status ='yyyy'");
      Thread th2 = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            psInsert1.setInt(1, 7);
            psInsert1.setInt(2, 7);
            psInsert1.setInt(3, -1 * 7);
            psInsert1.setInt(4, 100 * 7);
            psInsert1.setFloat(5, 40.40f);
            psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
            psInsert1.setString(7, "yyyy");
            psInsert1.setInt(8, 5);
            psInsert1.executeUpdate();
          } catch (Exception e) {
            e.printStackTrace();
            fail(e.toString());
          }

        }
      });
      th2.start();
      th2.join();
      conn.commit();
      psInsert1.setInt(1, 8);
      psInsert1.setInt(2, 8);
      psInsert1.setInt(3, -1 * 8);
      psInsert1.setInt(4, 100 * 8);
      psInsert1.setFloat(5, 40.40f);
      psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert1.setString(7, "yyyy");
      psInsert1.setInt(8, 5);
      psInsert1.executeUpdate();

      rs1 = s1.executeQuery("select * from trade.buyorders where "
          + "status = 'yyyy'");
      num = 0;
      while (rs1.next()) {
        assertEquals("yyyy", rs1.getString("status"));
        ++num;
      }
      assertEquals(2, num);
      this.doOffHeapValidations();
      psInsert.setInt(1, 9);
      psInsert.setInt(2, 9);
      psInsert.setInt(3, -1 * 9);
      psInsert.setInt(4, 100 * 9);
      psInsert.setFloat(5, 30.40f);
      psInsert.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert.setString(7, "wwww");
      psInsert.setInt(8, 5);
      psInsert.executeUpdate();

      Thread th3 = new Thread(new Runnable() {
        @Override
        public void run() {
          try {
            psInsert1.setInt(1, 10);
            psInsert1.setInt(2, 10);
            psInsert1.setInt(3, -1 * 10);
            psInsert1.setInt(4, 100 * 10);
            psInsert1.setFloat(5, 40.40f);
            psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
            psInsert1.setString(7, "wwww");
            psInsert1.setInt(8, 5);
            psInsert1.executeUpdate();
          } catch (Exception e) {
            e.printStackTrace();
            fail(e.toString());
          }

        }
      });
      th3.start();
      th3.join();

      conn.rollback();
      psInsert1.setInt(1, 11);
      psInsert1.setInt(2, 11);
      psInsert1.setInt(3, -1 * 11);
      psInsert1.setInt(4, 100 * 11);
      psInsert1.setFloat(5, 40.40f);
      psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert1.setString(7, "wwww");
      psInsert1.setInt(8, 5);
      psInsert1.executeUpdate();

      rs1 = s1.executeQuery("select * from trade.buyorders where "
          + "status = 'wwww'");
      num = 0;
      while (rs1.next()) {
        assertEquals("wwww", rs1.getString("status"));
        ++num;
      }
      assertEquals(2, num);
      s1.executeUpdate("delete from trade.buyorders");
      psInsert1.setInt(1, 1);
      psInsert1.setInt(2, 1);
      psInsert1.setInt(3, -1 * 1);
      psInsert1.setInt(4, 100 * 1);
      psInsert1.setFloat(5, 40.40f);
      psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert1.setString(7, "wwww");
      psInsert1.setInt(8, 5);
      psInsert1.executeUpdate();

      s.executeUpdate("update trade.buyorders set tid = 14");
      conn.commit();
      psInsert1.setInt(1, 2);
      psInsert1.setInt(2, 2);
      psInsert1.setInt(3, -1 * 2);
      psInsert1.setInt(4, 100 * 2);
      psInsert1.setFloat(5, 40.40f);
      psInsert1.setTimestamp(6, new Timestamp(System.currentTimeMillis()));
      psInsert1.setString(7, "wwww");
      psInsert1.setInt(8, 5);
      psInsert1.executeUpdate();

      if (s != null) {
        s.execute("drop table if exists trade.buyorders");
        this.waitTillAllClear();
      }

    } finally {
      try {
        DriverManager.getConnection("jdbc:derby:;shutdown=true");
      } catch (SQLException sqle) {
        if (sqle.getMessage().indexOf("shutdown") == -1) {
          sqle.printStackTrace();
          throw sqle;
        }
      }
      GemFireXDQueryObserverHolder.clearInstance();
    }

  }
  
  @Override
  public String getSuffix() {
    return  " offheap ";
  }
  
  @Override
  public void tearDown() throws Exception {
    LocalRegion.ISSUE_CALLBACKS_TO_CACHE_OBSERVER = false;
	CacheObserverHolder.setInstance(null);
	GemFireXDQueryObserverHolder.clearInstance();  
    super.tearDown();
    System.clearProperty("gemfire.OFF_HEAP_TOTAL_SIZE");
    System.clearProperty("gemfire."+DistributionConfig.OFF_HEAP_MEMORY_SIZE_NAME);
    System.clearProperty(GfxdManagementService.DISABLE_MANAGEMENT_PROPERTY);
  }
  
  @Override
  public void waitTillAllClear() {
	try {  
      rmcd.waitTillAllClear();
	}catch(InterruptedException ie) {
	  Thread.currentThread().interrupt();
	  throw new GemFireXDRuntimeException(ie);
	}
  }
  
  
}
