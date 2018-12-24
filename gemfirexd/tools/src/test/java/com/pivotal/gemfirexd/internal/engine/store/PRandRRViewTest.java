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
package com.pivotal.gemfirexd.internal.engine.store;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.Statement;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;

import com.gemstone.gemfire.internal.cache.LocalRegion;
import com.gemstone.gemfire.internal.cache.PartitionedRegion;
import com.gemstone.gemfire.internal.cache.execute.InternalRegionFunctionContext;
import com.pivotal.gemfirexd.TestUtil;
import com.pivotal.gemfirexd.internal.engine.Misc;
import com.pivotal.gemfirexd.internal.engine.GemFireXDQueryObserverHolder;
import com.pivotal.gemfirexd.internal.iapi.types.RowLocation;
import com.pivotal.gemfirexd.internal.impl.jdbc.EmbedConnection;
import com.pivotal.gemfirexd.jdbc.JdbcTestBase;

/**
 * 
 * @author Asif
 *
 */
public class PRandRRViewTest extends JdbcTestBase
{

  public PRandRRViewTest(String name) {
    super(name);
  }

  public void testReplicatedViewIteratorForViewTypes() throws Exception {
    try {
      java.sql.Connection conn = getConnection();
      String schema = TestUtil.getCurrentDefaultSchemaName();
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      Statement st = conn.createStatement();
      st.execute("Create table t1 (c1 int not null , c2 int not null, "
          + "c3 int not null, c4 int not null , c5 int not null, "
          + "primary key(c1)) replicate");
      Set<Integer> committedKeys = new HashSet<Integer>();
      Set<Integer> txKeys = new HashSet<Integer>();
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i < 21; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();
        committedKeys.add(i);
      }
      conn.commit();
      for (int i = 21; i < 41; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();
        txKeys.add(i);
      }
      String path = Misc.getRegionPath(schema, "T1", null);
      LocalRegion rgn = (LocalRegion)Misc.getRegion(path, true, false);

      // we should get 20 rows
      Iterator<?> itr = rgn.getSharedDataView().getLocalEntriesIterator(
          (InternalRegionFunctionContext)null, true, false, true, rgn);
      int cnt = 0;
      try {
        OUTERLOOP:
        while (itr.hasNext()) {
          RowLocation rl;
          for (;;) {
            rl = (RowLocation)itr.next();
            // locked entries appear as rows with removed token so skip those
            if (!rl.getRegionEntry().isRemoved()) {
              break;
            }
            else if (!itr.hasNext()) {
              break OUTERLOOP;
            }
          }
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(committedKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(committedKeys.isEmpty());

      assertEquals(20, cnt);
      // Region#values() should see both committed and non-committed data
      assertEquals(40, rgn.values().size());

      /*
      // Test tx only data

      // we should get 20 rows
      itr = ViewType.TX_ONLY.getIterator(rgn, true);
      cnt = 0;
      try {
        for (int i = 1; i < 21; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      assertEquals(20, cnt);
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      */

      // Test tx + commited data
      // we should get 40 rows
      for (int i = 1; i < 21; ++i) {
        committedKeys.add(i);
      }

      for (int i = 21; i < 41; ++i) {
        txKeys.add(i);
      }
      itr = rgn.getDataView().getLocalEntriesIterator(
          (InternalRegionFunctionContext)null, true, false, true, rgn);
      cnt = 0;
      try {
        for (int i = 1; i < 41; ++i) {
          RowLocation rl = (RowLocation)itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key) || committedKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      assertEquals(40, cnt);
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      assertTrue(committedKeys.isEmpty());

      /*
      // Test tx only data for local index scan
      // view = new GfxdReplicatedTableView(rgn,
      // AbstractTableView.TX_ONLY_FOR_LOCAL_INDEX);
      Set<Object> deletedTxKeys = new HashSet<Object>();
      // we should get 20 rows
      itr = ViewType.TX_ONLY_FOR_LOCAL_INDEX.getIteratorForLocalIndex(rgn,
          deletedTxKeys);
      for (int i = 21; i < 41; ++i) {
        txKeys.add(i);
      }
      cnt = 0;
      try {
        for (int i = 1; i < 21; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      assertEquals(20, cnt);
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      assertTrue(deletedTxKeys.isEmpty());
      */

    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testReplicatedViewIteratorWithTxOps() throws  Exception {

    try {
      java.sql.Connection conn = getConnection();
      String schema = TestUtil.getCurrentDefaultSchemaName();
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Statement st = conn.createStatement();
      st
          .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null,"
              + " c4 int not null , c5 int not null ," + " primary key(c1) ) replicate ");
      Set<Integer> committedKeys = new HashSet<Integer>();
      Set<Integer> txKeys = new HashSet<Integer>();
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i < 41; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();
        committedKeys.add(i);
      }
      conn.commit();
      Statement stmt = conn.createStatement();
      //update rows from i = 10 to i = 30      
      assertEquals(20, stmt.executeUpdate("update t1 set c2 = -10 where c1 > 10 and c1 < 31 "));
      for(int i=11;i < 31;++i) {
        txKeys.add(i);
      }
      String path = Misc.getRegionPath(schema, "T1", null);
      LocalRegion rgn = (LocalRegion)Misc.getRegion(path, true, false);
      getGFT((EmbedConnection)conn);
      /*
      Iterator<RowLocation> itr =  ViewType.TX_ONLY. getIterator(rgn, true);
      // we should get 20 rows
      
      int cnt = 0;
      try {
        for (int i = 11; i < 31; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      assertTrue(((GfxdRegionEntryIterator)itr).getTxKeysProcessed().isEmpty());
      assertTrue(((GfxdRegionEntryIterator)itr).getDeletedTxKeys().isEmpty());
      */
      
      //Test tx + commited only data
      for(int i=1;i < 41;++i) {
        txKeys.add(i);
      }
      // we should get 40 rows
      Iterator<?> itr = rgn.getDataView().getLocalEntriesIterator(
          (InternalRegionFunctionContext)null, true, false, true, rgn);
      int cnt = 0;
      try {
        for (int i = 1; i < 41; ++i) {
          RowLocation rl = (RowLocation)itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      //assertEquals(view.txKeysProcessed.size(),20);

      // delete some tx and some committed rows
      assertEquals(10,
          stmt.executeUpdate("delete from t1 where c1 > 25 and c1 < 36 "));

      /*
      // again check for TX data only data view      
      for(int i=11;i < 26;++i) {
        txKeys.add(i);
      }
      itr =  ViewType.TX_ONLY. getIterator(rgn, true);
//      view = new GfxdReplicatedTableView(rgn,
//          AbstractTableView.TX_ONLY);      
      cnt = 0;
      try {
        for (int i = 11; i < 26; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      assertTrue(((GfxdRegionEntryIterator)itr).getTxKeysProcessed().isEmpty());
      assertTrue(((GfxdRegionEntryIterator)itr).getDeletedTxKeys().isEmpty());

   // again check for TX data only data view for index      
      for(int i=11;i < 26;++i) {
        txKeys.add(i);
      }
//      view = new GfxdReplicatedTableView(rgn,
//          AbstractTableView.TX_ONLY_FOR_LOCAL_INDEX);
      Set<Object> deletedtxKeys = new HashSet<Object>();
      itr =  ViewType.TX_ONLY_FOR_LOCAL_INDEX.getIteratorForLocalIndex(
          rgn, deletedtxKeys);
      //itr = view.getIterator();
      cnt = 0;
      try {
        for (int i = 11; i < 26; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      
      assertTrue(((GfxdRegionEntryIterator)itr).getTxKeysProcessed().isEmpty());
      assertEquals(deletedtxKeys.size(),10);      
      for(int i=26;i < 36;++i) {
        txKeys.add(i);
      }
      
      for(Object deletedKey:deletedtxKeys) {
        Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
        assertTrue(txKeys.remove(key));
      }
      assertTrue(txKeys.isEmpty());
      */
      
      //Test tx + commited only data
      for(int i=1;i < 41;++i) {
        txKeys.add(i);
      }
      ///minus the deleted keys
      for(int i=26;i < 36;++i) {
        txKeys.remove(i);
      }
      // we should get 30 rows
      itr = rgn.getDataView().getLocalEntriesIterator(
          (InternalRegionFunctionContext)null, true, false, true, rgn);
      cnt = 0;
      try {
        for (int i = 1; i < 31; ++i) {
          RowLocation rl = (RowLocation)itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
    }
    finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testPartitionedViewIteratorForViewTypesPrimaryOnly()
      throws Exception {
    try {
      java.sql.Connection conn = getConnection();
      String schema = TestUtil.getCurrentDefaultSchemaName();
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      conn.setAutoCommit(false);
      Statement st = conn.createStatement();
      st.execute("Create table t1 (c1 int not null , c2 int not null, "
          + "c3 int not null, c4 int not null , c5 int not null ,"
          + " primary key(c1) ) ");
      Set<Integer> committedKeys = new HashSet<Integer>();
      Set<Integer> txKeys = new HashSet<Integer>();
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i < 401; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();
        committedKeys.add(i);
      }
      conn.commit();
      for (int i = 401; i < 501; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();
        txKeys.add(i);
      }
      String path = Misc.getRegionPath(schema, "T1", null);
      PartitionedRegion rgn = (PartitionedRegion)Misc.getRegion(path, true, false);

      // txn.setUpTransactionState();
      // GfxdPartitionedTableView view = new GfxdPartitionedTableView(rgn,true
      // /* primary only*/,
      // AbstractTableView.COMMITTED_ONLY);
      // we should get 20 rows
      Iterator<?> itr = rgn.getSharedDataView().getLocalEntriesIterator(
          (InternalRegionFunctionContext)null, true, false, true, rgn);
      int cnt = 0;
      try {
        for (int i = 1; i < 401; ++i) {
          RowLocation rl;
          for (;;) {
            assertTrue(itr.hasNext());
            rl = (RowLocation)itr.next();
            // locked entries appear as rows with removed token so skip those
            if (!rl.getRegionEntry().isRemoved()) {
              break;
            }
          }
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(committedKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      while (itr.hasNext()) {
        RowLocation rl = (RowLocation)itr.next();
        // locked entries appear as rows with removed token so skip those
        if (!rl.getRegionEntry().isRemoved()) {
          fail("Unexpected entry in iteration: " + rl);
        }
      }
      assertTrue(committedKeys.isEmpty());

      // Test tx only data

      // view = new GfxdPartitionedTableView(rgn,true /* primary only*/,
      // AbstractTableView.TX_ONLY);

      /*
      // we should get 20 rows
      itr = ViewType.TX_ONLY.getIterator(rgn, true);
      cnt = 0;
      try {
        for (int i = 401; i < 501; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      assertTrue(((GfxdRegionEntryIterator)itr).getDeletedTxKeys().isEmpty());
      */

      // Test tx + commited data
      // view = new GfxdPartitionedTableView(rgn,true/* primary only*/,
      // AbstractTableView.COMMITED_AND_TX);

      for (int i = 1; i < 401; ++i) {
        committedKeys.add(i);
      }

      for (int i = 401; i < 501; ++i) {
        txKeys.add(i);
      }
      itr = rgn.getDataView().getLocalEntriesIterator(
          (InternalRegionFunctionContext)null, true, false, true, rgn);
      cnt = 0;
      try {
        for (int i = 1; i < 501; ++i) {
          assertTrue(itr.hasNext());
          RowLocation rl = (RowLocation)itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key) || committedKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      assertTrue(committedKeys.isEmpty());

      /*
      // Test tx only data for local index scan
      // view = new GfxdPartitionedTableView(rgn, true,
      // AbstractTableView.TX_ONLY_FOR_LOCAL_INDEX);
      // we should get 20 rows
      Set<Object> deletedTxKeys = new HashSet<Object>();
      itr = ViewType.TX_ONLY_FOR_LOCAL_INDEX.getIteratorForLocalIndex(rgn,
          deletedTxKeys);

      for (int i = 401; i < 501; ++i) {
        txKeys.add(i);
      }
      cnt = 0;
      try {
        for (int i = 401; i < 501; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
      assertTrue(deletedTxKeys.isEmpty());
      */
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }

  public void testPartitionedViewIteratorWithTxOps() throws  Exception {

    try {
      java.sql.Connection conn = getConnection();
      String schema = TestUtil.getCurrentDefaultSchemaName();
      conn.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
      Statement st = conn.createStatement();
      st
          .execute("Create table t1 (c1 int not null , c2 int not null, c3 int not null,"
              + " c4 int not null , c5 int not null ," + " primary key(c1) )  ");
      Set<Integer> committedKeys = new HashSet<Integer>();
      Set<Integer> txKeys = new HashSet<Integer>();
      conn.commit();
      PreparedStatement pstmt = conn
          .prepareStatement("insert into t1 values(?,?,?,?,?)");
      for (int i = 1; i < 501; ++i) {
        pstmt.setInt(1, i);
        pstmt.setInt(2, i);
        pstmt.setInt(3, i);
        pstmt.setInt(4, i);
        pstmt.setInt(5, i);
        pstmt.executeUpdate();
        committedKeys.add(i);
      }
      conn.commit();
      Statement stmt = conn.createStatement();
      //update 200 rows from i = 101 to i = 300      
      assertEquals(200, stmt.executeUpdate("update t1 set c2 = -10 where c1 > 100 and c1 < 301 "));
      for(int i=101;i < 301;++i) {
        txKeys.add(i);
      }
      String path = Misc.getRegionPath(schema, "T1", null);
      PartitionedRegion rgn = (PartitionedRegion)Misc.getRegion(path, true, false);
      getGFT((EmbedConnection)conn);
      /*
      // we should get 200 rows
      Iterator<RowLocation> itr = ViewType.TX_ONLY.getIterator(rgn,true);
      int cnt = 0;
      try {
        for (int i = 101; i < 301; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());      
      assertTrue(((GfxdRegionEntryIterator)itr).getDeletedTxKeys().isEmpty());
      */
      
      //Test tx + commited only data
      for(int i=1;i < 501;++i) {
        txKeys.add(i);
      }
//      view = new GfxdPartitionedTableView(rgn, true, 
//          AbstractTableView.COMMITED_AND_TX);
      
      // we should get 500 rows
      Iterator<?> itr = rgn.getDataView().getLocalEntriesIterator(
          (InternalRegionFunctionContext)null, true, false, true, rgn);
      int cnt = 0;
      try {
        for (int i = 1; i < 501; ++i) {
          assertTrue(itr.hasNext());
          RowLocation rl = (RowLocation)itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());

      // delete 100 tx and some committed rows
      assertEquals(100,
          stmt.executeUpdate("delete from t1 where c1 > 250 and c1 < 351 "));

      /*
      // again check for TX data only data view      
      for(int i=101 ;i < 251 ;++i) {
        txKeys.add(i);
      }
//      view = new GfxdPartitionedTableView(rgn, true, AbstractTableView.TX_ONLY);
      itr = ViewType.TX_ONLY.getIterator(rgn,true);
      cnt = 0;
      try {
        for (int i = 101; i < 251; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());      
      assertTrue(((GfxdRegionEntryIterator)itr).getDeletedTxKeys().isEmpty());

   // again check for TX data only data view for index      
      for(int i=101;i < 251;++i) {
        txKeys.add(i);
      }
//      view = new GfxdPartitionedTableView(rgn,true,
//          AbstractTableView.TX_ONLY_FOR_LOCAL_INDEX);

      Set<Object> deletedTxKeys = new HashSet<Object>();
      itr = ViewType.TX_ONLY_FOR_LOCAL_INDEX.getIteratorForLocalIndex(rgn, deletedTxKeys);
      cnt = 0;
      try {
        for (int i = 101; i < 251; ++i) {
          RowLocation rl = itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue(txKeys.remove(key));
        }
      }
      finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());      
      assertEquals(deletedTxKeys.size(),100);      
      
      for(int i=251;i < 351;++i) {
        txKeys.add(i);
      }
      
      for(Object deletedKey:deletedTxKeys) {
        Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
        assertTrue(txKeys.remove(key));
      }
      assertTrue(txKeys.isEmpty());
      */

      //Test tx + commited only data
      for(int i=1;i < 501;++i) {
        txKeys.add(i);
      }
      ///minus the deleted keys
      for(int i=251;i < 351;++i) {
        txKeys.remove(i);
      }
      // we should get 400 rows
      itr = rgn.getDataView().getLocalEntriesIterator(
          (InternalRegionFunctionContext)null, true, false, true, rgn);
      cnt = 0;
      try {
        for (int i = 1; i < 401; ++i) {
          assertTrue(itr.hasNext());
          RowLocation rl = (RowLocation)itr.next();
          Integer key = ((RegionKey)rl.getKeyCopy()).getKeyColumn(0).getInt();
          ++cnt;
          assertTrue("unexpected key " + key, txKeys.remove(key));
        }
      } finally {
        System.out.println("num rows found=" + cnt);
      }
      assertFalse(itr.hasNext());
      assertTrue(txKeys.isEmpty());
    } finally {
      GemFireXDQueryObserverHolder.clearInstance();
    }
  }
}
